package com.amazon.awsgurufrontendservice.codecommit.inference.tests;

import com.amazon.awsgurufrontendservice.CodeCommitTestSuite;
import com.amazon.awsgurufrontendservice.codecommit.CodeCommitCommiter;
import com.amazonaws.services.codecommit.model.PullRequest;
import com.amazonaws.services.gurufrontendservice.model.CodeReview;
import com.amazonaws.services.gurufrontendservice.model.ConflictException;
import com.amazonaws.services.gurufrontendservice.model.ProviderType;
import com.amazonaws.services.gurufrontendservice.model.ResourceNotFoundException;
import com.amazonaws.services.gurufrontendservice.model.Type;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.GetCallerIdentityRequest;
import lombok.NonNull;
import lombok.extern.log4j.Log4j2;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Log4j2
@Test(groups = {"inference-integ"})
public class RecommendationsInferenceCodeCommitTestSuite extends CodeCommitTestSuite {

    private static final String RECOMMENDATION_SOURCE_BRANCH_NAME = String.format("RecommendationFeature-%s", System.currentTimeMillis());
    private String accountId;
    private String repositoryName;
    private CodeReview codeReview;
    private String codeReviewArn;
    private final ProviderType providerType = ProviderType.CodeCommit;
    private final CodeCommitCommiter codeCommitCommiter;
    private final String targetRepoPath = "tst-resource/billlingV2Repository";
    private String associationArn;

    @Parameters({"domain", "region"})
    public RecommendationsInferenceCodeCommitTestSuite(
            final String domain, final String region) {
        super(domain, region);
        this.codeCommitCommiter = new CodeCommitCommiter(this.getCodeCommitClient());
    }

    @Parameters({"domain", "region"})
    @BeforeClass(groups = {"canary", "codecommit-pullrequest-inference-canary"})
    public void beforeClass(final String domain, final String region) throws Exception {

        cleanupOldRepos();
        refreshGuruClient();
        this.accountId = AWSSecurityTokenServiceClientBuilder.standard().withRegion(region).build()
                .getCallerIdentity(new GetCallerIdentityRequest()).getAccount();
        this.repositoryName = String.format("%s-%s-%s-%s", DELETE_INFERENCE_REPOSITORY_NAME_PREFIX,
                CODECOMMIT_RECOMMENDATIONFEEDBACK_REPOSITORY_NAME_PREFIX, domain, region);
        log.info("Setting up CodeCommit codereview for repository {} ", this.repositoryName);
        createRepository(this.repositoryName);
        try {
            associationArn = onboard(this.repositoryName, null);
        } catch (ConflictException exception){
            log.info("Repository {} is already associated.", this.repositoryName);
        }
        PullRequest pullRequest = setupCodeReviewCodeCommit(this.repositoryName);
        try {
            this.codeReview = waitAndGetCodeReview(accountId, Type.PullRequest, pullRequest.getPullRequestTargets().get(0).getSourceCommit(),
                    this.providerType, pullRequest.getPullRequestId(), region, repositoryName);
            this.codeReviewArn = this.codeReview.getCodeReviewArn();
            waitForCodeGuruReviewerRecommendations(this.repositoryName, this.codeReviewArn);

            // verifies the billedLOC is stored in MDS correctly for codeCommit pull request

            verifyBilledLOCStoredInMDS(accountId,
                    getCodeReviewName(providerType, repositoryName, pullRequest.getPullRequestId(),
                            pullRequest.getPullRequestTargets().get(0).getSourceCommit()),
                            "PullRequest",
                            targetRepoPath, this.codeReview);
            codeReviewTest(this.repositoryName, codeReviewArn, this.providerType);
        } catch (final ResourceNotFoundException ex) { // Handling this to debug webhook issues.
            log.info("Repository {} is not deleted for further analysis.", repositoryName);
            throw ex;
        }
    }

    @AfterClass(groups = {"canary", "codecommit-pullrequest-inference-canary"})
    public void afterClass() throws Exception{
        if (associationArn != null) {
            disassociate(associationArn);
        }
        deleteRepository(repositoryName);
    }


    @Test(groups = {"canary", "codecommit-pullrequest-inference-canary"})
    public void codeReviewListPaginateCodeCommitTest() {
        codeReviewListPaginateTest(this.repositoryName, this.providerType);
    }

    @Test(groups = {"canary", "codecommit-pullrequest-inference-canary"})
    public void codeReviewCodeCommitTest() throws Exception {
        codeReviewTest(this.repositoryName, this.codeReviewArn, this.providerType);
    }

    /**
     * This method helps to create repository and associate it in Github.
     * It also creates pull requests.
     * TODO: craete CodeCommit pullrequest raiser. And try to move this method there, Also try create Facade pattern.
     * @param repositoryName
     * @return
     * @throws Exception
     */
    private PullRequest setupCodeReviewCodeCommit(
            @NonNull final String repositoryName) throws Exception {

        String destinationBranchCommitId = this.codeCommitCommiter.createBranchesAndReturnDestinationCommit(
                repositoryName, RECOMMENDATION_SOURCE_BRANCH_NAME, DESTINATION_BRANCH_NAME);
        this.codeCommitCommiter.createAndGetAfterCommit(RECOMMENDATION_SOURCE_BRANCH_NAME, destinationBranchCommitId, repositoryName);
        return this.codeCommitCommiter.createPullRequest(repositoryName, RECOMMENDATION_SOURCE_BRANCH_NAME, DESTINATION_BRANCH_NAME);
    }
}
