package com.amazon.awsgurufrontendservice.githubenterprise;

import com.amazon.awsgurufrontendservice.TestGitHubRepository;
import com.amazon.awsgurufrontendservice.github.GitHubCommitter;
import com.amazon.awsgurufrontendservice.model.CommentWithCategory;
import com.amazon.awsgurufrontendservice.pullrequest.GitHubPullRequestRaiser;
import com.amazonaws.guru.common.github.domain.GitHubPullRequest;
import com.amazonaws.guru.common.github.domain.GitHubRef;
import com.amazonaws.services.gurufrontendservice.model.CodeReview;
import com.amazonaws.services.gurufrontendservice.model.KMSKeyDetails;
import com.amazonaws.services.gurufrontendservice.model.ProviderType;
import com.amazonaws.services.gurufrontendservice.model.ResourceNotFoundException;
import com.amazonaws.services.gurufrontendservice.model.Type;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.GetCallerIdentityRequest;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import java.util.List;

@Slf4j
@Test(groups = {"githubenterprise-pullrequest-inference", "inference"})
public class GitHubEnterprisePRInferenceTestSuite extends GitHubEnterpriseTestSuite {

    private final static String DESTINATION_BRANCH_NAME = "master";
    private static final String RECOMMENDATION_SOURCE_BRANCH_NAME = String.format("RecommendationFeature-%s", System.currentTimeMillis());

    // Dynamo DB cache key for caching Association ID across test runs
    private static final String GHE_PR_TEST_CACHE_KEY = "GitHubEnterprisePullRequestTestSuite";
    private static final String GHE_PR_TEST_CACHE_KEY_CMCMK = "GitHubEnterprisePullRequestTestSuiteCMCMK";

    private String accountId;
    private final ProviderType providerType = ProviderType.GitHubEnterpriseServer;

    @Parameters({"domain", "region", "gitHubEnterpriseInferenceSecretId"})
    public GitHubEnterprisePRInferenceTestSuite(final String domain, final String region, @Optional final String gitHubEnterpriseSecretId) {
        super(domain, region, gitHubEnterpriseSecretId);
        this.accountId = AWSSecurityTokenServiceClientBuilder.standard().withRegion(region).build()
                .getCallerIdentity(new GetCallerIdentityRequest()).getAccount();
    }

    @Parameters({"domain", "region"})
    @Test(groups = {"canary", "githubenterprise-pullrequest-inference-canary"})
    public void pullRequest_GitHubEnterprise_SingleCommit(final String domain, final String region) throws Exception {
        String repositoryName = String.format("%s-GHEInferenceCanary-%s-%s", PERSISTENT_INFERENCE_REPOSITORY_NAME_PREFIX, domain, region).toLowerCase();
        pullRequest_GitHubEnterprise_SingleCommit_Helper(region, repositoryName, GHE_PR_TEST_CACHE_KEY, java.util.Optional.empty());
    }

    @Parameters({"domain", "region"})
    @Test(groups = {"canary", "githubenterprise-pullrequest-inference-canary"})
    public void pullRequest_GitHubEnterprise_SingleCommit_CMCMK(final String domain, final String region) throws Exception {
        String repositoryName = String.format("%s-GHEInferenceCanaryCMCMK-%s-%s", PERSISTENT_INFERENCE_REPOSITORY_NAME_PREFIX, domain, region).toLowerCase();
        pullRequest_GitHubEnterprise_SingleCommit_Helper(region, repositoryName, GHE_PR_TEST_CACHE_KEY_CMCMK, java.util.Optional.of(createKMSKeyDetailsCmCmk()));
    }

    private void pullRequest_GitHubEnterprise_SingleCommit_Helper(final String region, final String repositoryName, final String dynamoDbCacheKey,
                                                                  final java.util.Optional<KMSKeyDetails> keyDetails) throws Exception {
        //Note: not cleaning up old repos because the previous canary run might still not be completed before the next one starts as its running at 2/5Min rate.
        refreshGuruClient();

        // Using this flag to skip deletion of branch when 404 occurs
        boolean skipBranchDeletion = false;
        log.info("Setting up GitHubEnterprise codereview for repository {} ", repositoryName);
        try {
            createRepositoryIfNotExists(repositoryName);
            this.codeConnectHelper.waitForRepositoryToBeAvailable(repositoryName, getOwner(), credential.getConnectionArn());
            onboardRepositoryIfNotOnboarded(repositoryName, dynamoDbCacheKey, keyDetails);
            try {
                raise_pullRequest_and_verify(repositoryName, region);
            }  catch (final ResourceNotFoundException ex) { // Handling this to debug webhook issues.
                log.info("Branch {} in repository {} is not deleted for further analysis.", RECOMMENDATION_SOURCE_BRANCH_NAME, repositoryName);
                skipBranchDeletion = true;
                throw ex;
            }
        } finally {
            if (!skipBranchDeletion) {
                deleteBranch(repositoryName, RECOMMENDATION_SOURCE_BRANCH_NAME);
            }
        }
    }

    private void raise_pullRequest_and_verify(String repositoryName, String region) throws Exception {
        // setup github pull request raiser
        GitHubPullRequestRaiser gitHubPullRequestRaiser = setupGitHubPullRequestRaiser(repositoryName);
        GitHubPullRequest gitHubPullRequest = gitHubPullRequestRaiser.raisePullRequest();
        CodeReview codeReview = waitAndGetCodeReview(this.accountId, Type.PullRequest, gitHubPullRequest.getHead().getSha(),
                this.providerType, Integer.toString(gitHubPullRequest.getNumber()), region, repositoryName);
        String codeReviewArn = codeReview.getCodeReviewArn();

        //verifies the different categories of recommendations generated and feedback loop is functioning
        codeReviewTest(repositoryName, codeReviewArn, this.providerType);

        //verifies the recommendations generated are published
        final List<CommentWithCategory> commentsWithCategories = this.codeConnectHelper
                .waitForRecommendations(repositoryName, codeReview.getPullRequestId(),
                        getOwner(), credential.getConnectionArn());
        log.info("Received {} comments, verifying... ", commentsWithCategories.size());
        verifyComments(commentsWithCategories, repositoryName);
    }

    /**
     * This method helps to create repository and associate it in Github.
     * It also creates pull requests.
     * @param repositoryName
     * @return
     * @throws Exception
     */
    private GitHubPullRequestRaiser setupGitHubPullRequestRaiser(
            @NonNull final String repositoryName) throws Exception {

        TestGitHubRepository destinationRepository = TestGitHubRepository.builder()
                .name(repositoryName)
                .owner(getOwner())
                .branchName(DESTINATION_BRANCH_NAME)
                .gitHubAdminApiFacade(this.gitHubEnterpriseAdminApiFacade)
                .build();

        TestGitHubRepository sourceRepository = TestGitHubRepository.builder()
                .name(repositoryName)
                .owner(getOwner())
                .branchName(RECOMMENDATION_SOURCE_BRANCH_NAME)
                .gitHubAdminApiFacade(this.gitHubEnterpriseAdminApiFacade)
                .build();

        setupSourceBranchForPullRequest(DESTINATION_BRANCH_NAME, sourceRepository);
        return new GitHubPullRequestRaiser(
                destinationRepository,
                sourceRepository,
                this.gitHubEnterpriseApiFacade);
    }

    protected void setupSourceBranchForPullRequest(
            final String destinationBranchName,
            final TestGitHubRepository sourceRepository) throws Exception {
        GitHubCommitter sourceCommitter = new GitHubCommitter(sourceRepository);
        // Get the ref of master head (GetRef)
        final GitHubRef sourceFeatureRef = sourceCommitter.createBranch(sourceRepository.getBranchName(),
                destinationBranchName);

        String repositoryPath = "tst-resource/repository";
        sourceCommitter.commit(sourceFeatureRef, repositoryPath);
    }

}


