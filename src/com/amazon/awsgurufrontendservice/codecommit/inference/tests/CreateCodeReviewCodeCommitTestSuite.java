package com.amazon.awsgurufrontendservice.codecommit.inference.tests;

import com.amazon.awsgurufrontendservice.CodeCommitTestSuite;
import com.amazon.awsgurufrontendservice.codecommit.CodeCommitCommiter;
import com.amazonaws.services.gurufrontendservice.model.CodeReview;
import com.amazonaws.services.gurufrontendservice.model.ConflictException;
import com.amazonaws.services.gurufrontendservice.model.KMSKeyDetails;
import com.amazonaws.services.gurufrontendservice.model.ProviderType;
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
public class CreateCodeReviewCodeCommitTestSuite extends CodeCommitTestSuite {

    private final String accountId;
    private final String repositoryName;
    private final String codeReviewName;
    private final String repositoryNameCMCMK;
    private final String codeReviewNameCMCMK;
    private final ProviderType providerType = ProviderType.CodeCommit;
    private final CodeCommitCommiter codeCommitCommiter;
    private final String targetRepoPath = "tst-resource/billlingV2Repository";

    private static final String CODE_REVIEW_SOURCE_BRANCH_NAME = String.format("CodeReview-%s", System.currentTimeMillis());

    @Parameters({"domain", "region"})
    public CreateCodeReviewCodeCommitTestSuite(@NonNull final String domain,
                                               @NonNull final String region) {
        super(domain, region);
        this.accountId = AWSSecurityTokenServiceClientBuilder.standard().withRegion(region).build()
                .getCallerIdentity(new GetCallerIdentityRequest()).getAccount();
        this.repositoryName = String.format("%s-%s-%s-%s", DELETE_INFERENCE_REPOSITORY_NAME_PREFIX,
                CODECOMMIT_CREATE_CODEREVIEW_REPOSITORY_NAME_PREFIX, domain, region);
        this.repositoryNameCMCMK = String.format("CMCMK-%s-%s-%s-%s", DELETE_INFERENCE_REPOSITORY_NAME_PREFIX,
                CODECOMMIT_CREATE_CODEREVIEW_REPOSITORY_NAME_PREFIX, domain, region);
        this.codeReviewName = String.format("%s-%s-%s", repositoryName, CODE_REVIEW_SOURCE_BRANCH_NAME, System.currentTimeMillis());
        this.codeReviewNameCMCMK = String.format("%s-%s-%s", repositoryNameCMCMK, CODE_REVIEW_SOURCE_BRANCH_NAME, System.currentTimeMillis());
        this.codeCommitCommiter = new CodeCommitCommiter(this.getCodeCommitClient());
    }

    @Test(groups = {"canary", "codecommit-repositoryanalysis-inference-canary"})
    public void createCodeReviewCodeCommitTest() throws Exception {
        createCodeReviewCodeCommitTestHelper(this.repositoryName, this.codeReviewName, Optional.empty());
    }

    @Test(groups = {"canary", "codecommit-repositoryanalysis-inference-canary"})
    public void createCodeReviewCodeCommitTestCMCMK() throws Exception {
        createCodeReviewCodeCommitTestHelper(this.repositoryNameCMCMK, this.codeReviewNameCMCMK, Optional.of(createKMSKeyDetailsCmCmk()));
    }

    private void createCodeReviewCodeCommitTestHelper(final String repositoryName, final String codeReviewName, final Optional<KMSKeyDetails> kmsKeyDetails) throws Exception {
        cleanupOldRepos();
        refreshGuruClient();

        log.info("Starting create code review test for CodeCommit repository {} ", repositoryName);
        createRepository(repositoryName);
        try {
            String associationArn = null;
            try {
                associationArn = onboard(repositoryName, kmsKeyDetails, null);
            } catch (final ConflictException exception) {
                log.info("Repository {} is already associated.", repositoryName);
            }
            try {
                createCodeReviewAndVerify(repositoryName, associationArn, codeReviewName);
            } finally {
                if (associationArn != null) {
                    disassociate(associationArn);
                }
            }
        } finally {
            deleteRepository(repositoryName);
        }
    }

    private void createCodeReviewAndVerify(String repositoryName, String associationArn, String codeReviewName) throws Exception {
        Assert.assertNotNull(String.format("Repository %s is not assocaited", repositoryName), associationArn);
        String destinationBranchCommitId = this.codeCommitCommiter.createBranchesAndReturnDestinationCommit(
                repositoryName, CODE_REVIEW_SOURCE_BRANCH_NAME, DESTINATION_BRANCH_NAME);
        this.codeCommitCommiter.createAndGetAfterCommit(CODE_REVIEW_SOURCE_BRANCH_NAME, destinationBranchCommitId, repositoryName);

        final String codeReviewArn = validateAndCreateCodeReview(associationArn, codeReviewName, CODE_REVIEW_SOURCE_BRANCH_NAME);
        CodeReview codeReview = waitAndGetCodeReview(codeReviewArn);
        waitAndGetCodeReview(codeReviewArn);
        waitForCodeGuruReviewerRecommendations(repositoryName, codeReviewArn);

        // verifies the billedLOC is stored in MDS correctly for codeCommit repo analysis
        verifyBilledLOCStoredInMDS(this.accountId,
                codeReviewName,
                "OnDemand",
                this.targetRepoPath, codeReview);
        codeReviewTest(repositoryName, codeReviewArn, this.providerType);
    }
}
