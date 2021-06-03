package com.amazon.awsgurufrontendservice.bitbucket.inference.test;

import com.amazon.awsgurufrontendservice.bitbucket.BitbucketTestSuite;
import com.amazonaws.services.gurufrontendservice.model.AssociateRepositoryResult;
import com.amazonaws.services.gurufrontendservice.model.CodeReview;
import com.amazonaws.services.gurufrontendservice.model.DescribeRepositoryAssociationResult;
import com.amazonaws.services.gurufrontendservice.model.KMSKeyDetails;
import com.amazonaws.services.gurufrontendservice.model.ProviderType;
import com.amazonaws.services.gurufrontendservice.model.ResourceNotFoundException;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.GetCallerIdentityRequest;
import lombok.NonNull;
import lombok.extern.log4j.Log4j2;
import org.testng.Assert;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import java.util.Optional;

@Log4j2
@Test(groups = {"inference-integ"})
public class CreateCodeReviewBitbucketTestSuite extends BitbucketTestSuite {

    private final String accountId;
    private final String repositoryName;
    private final String codeReviewName;
    private final String repositoryNameCMCMK;
    private final String codeReviewNameCMCMK;
    private final ProviderType providerType = ProviderType.Bitbucket;
    private static final String CODE_REVIEW_SOURCE_BRANCH_NAME = String.format("CodeReview-%s", System.currentTimeMillis());
    private final String targetRepoPath = "tst-resource/billlingV2Repository";

    @Parameters({"domain", "region", "bitbucketInferenceSecretId"})
    public CreateCodeReviewBitbucketTestSuite(
            @NonNull final String domain,
            @NonNull final String region,
            @NonNull final String bitbucketSecretId) {
        super(domain, region, bitbucketSecretId);
        this.accountId = AWSSecurityTokenServiceClientBuilder.standard().withRegion(region).build()
                .getCallerIdentity(new GetCallerIdentityRequest()).getAccount();
        this.repositoryName = String.format("%s-%s-%s-%s", DELETE_INFERENCE_REPOSITORY_NAME_PREFIX,
                BITBUCKET_CREATE_CODEREVIEW_REPOSITORY_NAME_PREFIX, domain, region);
        this.codeReviewName = String.format("%s-%s-%s", repositoryName, CODE_REVIEW_SOURCE_BRANCH_NAME, System.currentTimeMillis());
        this.repositoryNameCMCMK = String.format("CMCMK-%s-%s-%s-%s", DELETE_INFERENCE_REPOSITORY_NAME_PREFIX,
                BITBUCKET_CREATE_CODEREVIEW_REPOSITORY_NAME_PREFIX, domain, region);
        this.codeReviewNameCMCMK = String.format("%s-%s-%s", repositoryNameCMCMK, CODE_REVIEW_SOURCE_BRANCH_NAME, System.currentTimeMillis());
    }

    @Test(groups = {"canary", "bitbucket-repositoryanalysis-inference-canary"})
    public void createCodeReviewBitbucketTest() throws Exception {
        createCodeReviewBitBucketTestHelper(this.repositoryName, this.codeReviewName, Optional.empty());
    }

    @Test(groups = {"canary", "bitbucket-repositoryanalysis-inference-canary"})
    public void createCodeReviewBitbucketTestCMCMK() throws Exception {
        createCodeReviewBitBucketTestHelper(this.repositoryNameCMCMK, this.codeReviewNameCMCMK, Optional.of(createKMSKeyDetailsCmCmk()));
    }

    private void createCodeReviewBitBucketTestHelper(final String repositoryName, final String codeReviewName, final Optional<KMSKeyDetails> keyDetails) throws Exception {
        refreshGuruClient();
        // Using this flag to skip deletion of repository when 404 occurs
        boolean skipRepositoryDeletion = false;
        log.info("Starting create code review test for Bitbucket repository {} ", repositoryName);
        createRepository(repositoryName);
        this.codeConnectHelper.waitForRepositoryToBeAvailable(repositoryName, getUsername(),
                credential.getConnectionArn());

        AssociateRepositoryResult associateRepositoryResult = onboard(repositoryName, getUsername(), keyDetails);
        try {
            final String associationArn = associateRepositoryResult.getRepositoryAssociation().getAssociationArn();
            try {
                createCodeReviewAndVerify(repositoryName, codeReviewName, associationArn);
            }   catch (final ResourceNotFoundException ex) { // Handling this to debug webhook issues.
                log.info("Repository {} is not deleted for further analysis.", repositoryName);
                skipRepositoryDeletion = true;
                throw ex;
            } finally {
                if (associationArn != null) {
                    disassociate(associationArn);
                }
            }
        } finally {
            if (!skipRepositoryDeletion) {
                deleteRepository(repositoryName);
            }
        }
    }

    private void createCodeReviewAndVerify(String repositoryName, String codeReviewName, String associationArn) throws Exception {
        Assert.assertNotNull(String.format("Repository %s is not assocaited", repositoryName), associationArn);
        createFirstCommit(repositoryName, INITIAL_FILE_TO_COMMIT, DEFAULT_DESTINATION_BRANCH_NAME);
        commitTestFiles(repositoryName, CODE_REVIEW_SOURCE_BRANCH_NAME, 1);
        String CodeReviewArn = validateAndCreateCodeReview(associationArn, codeReviewName, this.CODE_REVIEW_SOURCE_BRANCH_NAME);
        final CodeReview codeReview = waitAndGetCodeReview(CodeReviewArn);
        final String codeReviewArn = codeReview.getCodeReviewArn();
        waitAndGetCodeReview(codeReviewArn);
        waitForCodeGuruReviewerRecommendations(repositoryName, codeReviewArn);
        codeReviewTest(repositoryName, codeReviewArn, this.providerType);

        //verifies the billed LOC is correctly stored in MDS for pull request bb job
        verifyBilledLOCStoredInMDS(this.accountId,
                codeReviewName,
                "OnDemand",
                this.targetRepoPath, codeReview);
    }
}
