package com.amazon.awsgurufrontendservice.bitbucket;

import com.amazon.awsgurufrontendservice.model.CommentWithCategory;
import com.amazonaws.guru.common.bitbucket.domain.BitbucketPullRequest;
import com.amazonaws.services.gurufrontendservice.model.AssociateRepositoryResult;
import com.amazonaws.services.gurufrontendservice.model.CodeReview;
import com.amazonaws.services.gurufrontendservice.model.KMSKeyDetails;
import com.amazonaws.services.gurufrontendservice.model.ProviderType;
import com.amazonaws.services.gurufrontendservice.model.Type;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.GetCallerIdentityRequest;
import lombok.extern.log4j.Log4j2;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;

@Log4j2
@Test(groups = {"bitbucket-pullrequest-inference", "inference"})
public class BitbucketPullRequestInferenceTestSuite extends BitbucketTestSuite {
    private final static String INITIAL_FILE_TO_COMMIT = "tst-resource/repository/InitialCommitFile.java";
    private static final String RECOMMENDATION_SOURCE_BRANCH_NAME = String.format("RecommendationFeature-%s", System.currentTimeMillis());
    private final String targetRepoPath = "tst-resource/billlingV2Repository";
    private String accountId;
    private final ProviderType providerType = ProviderType.Bitbucket;


    @Parameters({"domain", "region", "bitbucketInferenceSecretId"})
    public BitbucketPullRequestInferenceTestSuite(final String domain, final String region, final String bitbucketInferenceSecretId) {
        super(domain, region, bitbucketInferenceSecretId);
        this.accountId = AWSSecurityTokenServiceClientBuilder.standard().withRegion(region).build()
                .getCallerIdentity(new GetCallerIdentityRequest()).getAccount();
    }


    @Parameters({"domain", "region"})
    @Test(groups = {"canary", "bitbucket-pullrequest-inference-canary"})
    public void pullRequest_WithBitbucket_SingleCommit(final String domain, final String region) throws Exception {
        String repositoryName = String.format("BBInferenceCanary-%s-%s-%s", domain, region, System.currentTimeMillis()).toLowerCase();
        pullRequest_WithBitbucket_SingleCommit_Helper(repositoryName, region, Optional.empty());
    }

    @Parameters({"domain", "region"})
    @Test(groups = {"canary", "bitbucket-pullrequest-inference-canary"})
    public void pullRequest_WithBitbucket_SingleCommit_CMCMK(final String domain, final String region) throws Exception {
        String repositoryName = String.format("BBInferenceCanaryCMCMK-%s-%s-%s", domain, region, System.currentTimeMillis()).toLowerCase();
        pullRequest_WithBitbucket_SingleCommit_Helper(repositoryName, region, Optional.of(createKMSKeyDetailsCmCmk()));
    }

    private void pullRequest_WithBitbucket_SingleCommit_Helper(final String repositoryName, final String region, final Optional<KMSKeyDetails> keyDetails) throws Exception {
        refreshGuruClient();

        log.info("Setting up Bitbucket codereview for repository {} ", repositoryName);
        createRepository(repositoryName);
        this.codeConnectHelper.waitForRepositoryToBeAvailable(repositoryName, getUsername(),
                credential.getConnectionArn());

        try {
            AssociateRepositoryResult associateRepositoryResult = onboard(repositoryName, getUsername(), keyDetails);
            String associationArn = associateRepositoryResult.getRepositoryAssociation().getAssociationArn();
            String connectionArn = associateRepositoryResult.getRepositoryAssociation().getConnectionArn();
            try {
                createCodeReview_And_Verify(repositoryName, region, connectionArn);
            } finally {
                if (associationArn != null) {
                    disassociate(associationArn);
                }
            }
        } finally {
            deleteRepository(repositoryName);
            log.info("Repository {} is deleted", repositoryName);
        }
    }

    private void createCodeReview_And_Verify(String repositoryName, String region, String connectionArn) throws Exception {
        createFirstCommit(repositoryName, INITIAL_FILE_TO_COMMIT, DEFAULT_DESTINATION_BRANCH_NAME);
        BitbucketPullRequest bitbucketPullRequest = createPullRequest(repositoryName,
                RECOMMENDATION_SOURCE_BRANCH_NAME,
                1,
                "Pull request for recommendations");

        String sourceFullHash = codeConnectHelper.getSourceFullHash(repositoryName, bitbucketPullRequest.getId(),
                getUsername(), connectionArn,
                bitbucketPullRequest.getSource().getCommit().getHash());

        CodeReview codeReview = waitAndGetCodeReview(this.accountId, Type.PullRequest, sourceFullHash,
                this.providerType, bitbucketPullRequest.getId(), region, repositoryName);
        String codeReviewArn = codeReview.getCodeReviewArn();

        //verifies the different categories of recommendations generated and feedback loop is functioning
        codeReviewTest(repositoryName, codeReviewArn, this.providerType);

        //verifies the recommendations generated are published
        final List<CommentWithCategory> commentsWithCategories = codeConnectHelper
                .waitForRecommendations(repositoryName, codeReview.getPullRequestId(),
                        this.credential.getUsername(), this.credential.getConnectionArn());
        log.info("Received {} comments, verifying... ", commentsWithCategories.size());
        verifyComments(commentsWithCategories, repositoryName);

        //verifies the billed LOC is correctly stored in MDS
        verifyBilledLOCStoredInMDS(this.accountId,
                getCodeReviewName(this.providerType, repositoryName, bitbucketPullRequest.getId(),sourceFullHash),
                "PullRequest",
                this.targetRepoPath, codeReview);
    }

}