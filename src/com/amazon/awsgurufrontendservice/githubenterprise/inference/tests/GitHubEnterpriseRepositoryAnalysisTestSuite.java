package com.amazon.awsgurufrontendservice.githubenterprise.inference.tests;

import com.amazon.awsgurufrontendservice.githubenterprise.GitHubEnterpriseTestSuite;
import com.amazonaws.services.gurufrontendservice.model.KMSKeyDetails;
import com.amazonaws.services.gurufrontendservice.model.ProviderType;
import com.amazonaws.services.gurufrontendservice.model.ResourceNotFoundException;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.GetCallerIdentityRequest;
import lombok.NonNull;
import lombok.extern.log4j.Log4j2;
import org.testng.Assert;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Log4j2
@Test(groups = {"inference-integ"})
public class GitHubEnterpriseRepositoryAnalysisTestSuite extends GitHubEnterpriseTestSuite {

    private final String accountId;
    private final String repositoryName;
    private final String codeReviewName;
    private final String repositoryNameCMCMK;
    private final String codeReviewNameCMCMK;
    private final ProviderType providerType = ProviderType.GitHubEnterpriseServer;
    private static final String CODE_REVIEW_SOURCE_BRANCH_NAME = String.format("CodeReview-%s", System.currentTimeMillis());

    // // Dynamo DB cache key for caching Association ID across test runs
    private static final String GHE_RA_TEST_CACHE_KEY = "GitHubEnterpriseRepositoryAnalysisTestSuite";
    private static final String GHE_RA_TEST_CACHE_KEY_CMCMK = "GitHubEnterpriseRepositoryAnalysisTestSuiteCMCMK";

    @Parameters({"domain", "region", "gitHubEnterpriseInferenceSecretId"})
    public GitHubEnterpriseRepositoryAnalysisTestSuite(
            @NonNull final String domain,
            @NonNull final String region,
            @Optional final String gitHubEnterpriseInferenceSecretId) {
        super(domain, region, gitHubEnterpriseInferenceSecretId);
        this.accountId = AWSSecurityTokenServiceClientBuilder.standard().withRegion(region).build()
                .getCallerIdentity(new GetCallerIdentityRequest()).getAccount();
        this.repositoryName = String.format("%s-GHERepoScan-%s-%s", PERSISTENT_INFERENCE_REPOSITORY_NAME_PREFIX, domain, region);
        this.codeReviewName = String.format("%s-%s", repositoryName, CODE_REVIEW_SOURCE_BRANCH_NAME);
        this.repositoryNameCMCMK = String.format("%s-GHERepoScanCMCMK-%s-%s", PERSISTENT_INFERENCE_REPOSITORY_NAME_PREFIX, domain, region);
        this.codeReviewNameCMCMK = String.format("%s-%s", repositoryNameCMCMK, CODE_REVIEW_SOURCE_BRANCH_NAME);
    }

    @Test(groups = {"canary", "githubenterprise-repositoryanalysis-inference-canary"})
    public void createCodeReviewGitHubEnterpriseTest() throws Exception {
        createCodeReviewGitHubEnterpriseHelper(this.repositoryName, this.codeReviewName, GHE_RA_TEST_CACHE_KEY, java.util.Optional.empty());
    }

    @Test(groups = {"canary", "githubenterprise-repositoryanalysis-inference-canary"})
    public void createCodeReviewGitHubEnterpriseTestCMCMK() throws Exception {
        createCodeReviewGitHubEnterpriseHelper(this.repositoryNameCMCMK, this.codeReviewNameCMCMK, GHE_RA_TEST_CACHE_KEY_CMCMK, java.util.Optional.of(createKMSKeyDetailsCmCmk()));
    }

    private void createCodeReviewGitHubEnterpriseHelper(String repositoryName, String codeReviewName, String dynamoDbCacheKey, java.util.Optional<KMSKeyDetails> keydetails) throws Exception {
        refreshGuruClient();

        // Using this flag to skip deletion of branch when 404 occurs
        boolean skipBranchDeletion = false;
        log.info("Creating code review {} for GitHubEnterprise repository {} using cache key {}", codeReviewName, repositoryName, dynamoDbCacheKey);
        try {
            createRepositoryIfNotExists(repositoryName);
            this.codeConnectHelper.waitForRepositoryToBeAvailable(repositoryName, getOwner(), credential.getConnectionArn());
            final String associationArn = onboardRepositoryIfNotOnboarded(repositoryName, dynamoDbCacheKey, keydetails);
            try {
                createCodeReviewAndVerify(repositoryName, associationArn, codeReviewName);
            }  catch (final ResourceNotFoundException ex) { // Handling this to debug webhook issues.
                log.info("Branch {} in repository {} is not deleted for further analysis.", CODE_REVIEW_SOURCE_BRANCH_NAME, repositoryName);
                skipBranchDeletion = true;
                throw ex;
            }
        } finally {
            if (!skipBranchDeletion) {
                deleteBranch(repositoryName, CODE_REVIEW_SOURCE_BRANCH_NAME);
            }
        }
    }

    private void createCodeReviewAndVerify(String repositoryName, String associationArn, String codeReviewName) throws Exception {
        Assert.assertNotNull(String.format("Repository %s is not associated", repositoryName), associationArn);
        // setup github pull request raiser
        setupGitHubPullRequestRaiser(this.CODE_REVIEW_SOURCE_BRANCH_NAME, repositoryName,
                getOwner(), this.gitHubEnterpriseApiFacade, this.gitHubEnterpriseAdminApiFacade);
        final String codeReviewArn = validateAndCreateCodeReview(associationArn, codeReviewName, this.CODE_REVIEW_SOURCE_BRANCH_NAME);
        waitAndGetCodeReview(codeReviewArn);
        waitForCodeGuruReviewerRecommendations(repositoryName, codeReviewArn);
        codeReviewTest(repositoryName, codeReviewArn, this.providerType);
    }
}
