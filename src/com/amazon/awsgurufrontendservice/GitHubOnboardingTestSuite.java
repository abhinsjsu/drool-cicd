package com.amazon.awsgurufrontendservice;

import com.amazonaws.services.gurufrontendservice.model.AssociateRepositoryRequest;
import com.amazonaws.services.gurufrontendservice.model.CreateConnectionTokenResult;
import com.amazonaws.services.gurufrontendservice.model.GitHubRepository;
import com.amazonaws.services.gurufrontendservice.model.KMSKeyDetails;
import com.amazonaws.services.gurufrontendservice.model.ProviderType;
import com.amazonaws.services.gurufrontendservice.model.Repository;
import com.amazonaws.services.gurufrontendservice.model.ValidationException;
import com.amazonaws.services.gurufrontendservice.model.ConflictException;
import lombok.extern.log4j.Log4j2;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Log4j2
@Test(groups = {"github-onboarding", "onboarding"})
public class GitHubOnboardingTestSuite extends GitHubTestSuite {

    @Parameters({"domain", "region", "secretId", "secretIdForkedRepo"})
    public GitHubOnboardingTestSuite(final String domain, final String region, @Optional final String secretId,
                                     @Optional final String secretIdForkedRepo) {
        super(domain, region, secretId, secretIdForkedRepo);
    }

    @Parameters({"domain", "region"})
    @Test(groups = {"canary", "github-onboarding-canary"})
    public void onboardGitHubRepositoryCMCMK(final String domain, final String region) throws Exception {
        String repositoryNameCMCMK = getRepositoryNameCMCMK(domain, region);
        onboardGitHubRepositoryTest(repositoryNameCMCMK, java.util.Optional.of(createKMSKeyDetailsCmCmk()));
    }


    @Parameters({"domain", "region"})
    @Test(groups = {"github-onboarding", "onboarding"})
    public void associateRepositoryTestGitHubCmCmk(final String domain,
                                             final String region) throws Exception {
        java.util.Optional<KMSKeyDetails> kmsKeyDetails = java.util.Optional.of(createKMSKeyDetailsCmCmk());
        onboardGitHubRepositoryTest(domain, region, kmsKeyDetails);
    }

    @Parameters({"domain", "region"})
    @Test(groups = {"github-onboarding", "onboarding"})
    public void associateRepositoryTestGitHubAoCmk(final String domain,
                                             final String region) throws Exception {
        java.util.Optional<KMSKeyDetails> kmsKeyDetails = java.util.Optional.of(createKMSKeyDetailsAoCmk());
        onboardGitHubRepositoryTest(domain, region, kmsKeyDetails);
    }


    @Parameters({"domain", "region"})
    @Test(expectedExceptions = ValidationException.class)
    public void associateRepositoryTestGitHubCmCmkWithoutKeyId(final String domain,
                                                   final String region) throws Exception {
        java.util.Optional<KMSKeyDetails> kmsKeyDetails = java.util.Optional.of(createKMSKeyDetailsCmCmk());
        kmsKeyDetails.get().setKMSKeyId(null);
        onboardGitHubRepositoryTest(domain, region, kmsKeyDetails);
    }
    //
    @Parameters({"domain", "region"})
    @Test(expectedExceptions = ValidationException.class)
    public void associateRepositoryTestGitHubAoCmkWithKeyId(final String domain,
                                                   final String region) throws Exception {
        java.util.Optional<KMSKeyDetails> kmsKeyDetails = java.util.Optional.of(createKMSKeyDetailsAoCmk());
        kmsKeyDetails.get().setKMSKeyId("fakeKeyId");
        onboardGitHubRepositoryTest(domain, region, kmsKeyDetails);
    }

    private void onboardGitHubRepositoryTest(final String domain, final String region,
                                             final java.util.Optional<KMSKeyDetails> kmsKeyDetails) throws Exception {
        String repositoryName = getRepositoryName(domain, region);
        onboardGitHubRepositoryTest(repositoryName, kmsKeyDetails);
    }

    private void onboardGitHubRepositoryTest(final String repositoryName,
                                             final java.util.Optional<KMSKeyDetails> kmsKeyDetails) throws Exception {
        refreshGuruClient();
        String associationArn = null;

        try {
            createRepository(repositoryName);
            CreateConnectionTokenResult connectionToken = fetchConnectionToken();
            verifyRepositoryIsVisible(repositoryName, connectionToken);
            associationArn = onboard(repositoryName, getOwner(), connectionToken.getConnectionToken(), kmsKeyDetails);
            verifyIsListed(repositoryName, ProviderType.GitHub);
        } finally {
            try {
                if (associationArn != null) {
                    disassociate(associationArn);
                }
            } finally {
                deleteRepository(repositoryName);
            }
        }
    }

    @Test(expectedExceptions = ValidationException.class)
    public void emptyRepoNameTest() {
        AssociateRepositoryRequest associationsRequest = new AssociateRepositoryRequest();
        associationsRequest.setRepository(new Repository().withGitHub(
            new GitHubRepository().withName("")));
        getGuruClient().associateRepository(associationsRequest);
    }

    @Test(expectedExceptions = ValidationException.class)
    public void nullRepoNameTest() {
        AssociateRepositoryRequest associationsRequest = new AssociateRepositoryRequest();
        associationsRequest.setRepository(new Repository().withGitHub(
            new GitHubRepository()));
        getGuruClient().associateRepository(associationsRequest);
    }

    @Test(expectedExceptions = ValidationException.class)
    public void onboard_InvalidConnectionTokenTest() throws InterruptedException {
        String repositoryName = String.format("GHOnboardingInvalidConnectionToken");
        CreateConnectionTokenResult connectionToken = fetchConnectionToken("invalid-token",
            "invalid-scope", "invalid-user");
        //this call will fail if we use an invalid connection token
        verifyRepositoryIsVisible(repositoryName, connectionToken);
    }

    @Parameters({"domain"})
    @Test(expectedExceptions = ConflictException.class,
            expectedExceptionsMessageRegExp = "^An association for this repository already exists\\. If the association has failed, " +
            "disassociate the repository before attempting to associate the repository again\\. (.*)$")
    public void associationAlreadyExistTest(final String domain) throws Exception {
        refreshGuruClient();
        String repositoryName = String.format("GHOnboarding%s%d", domain, System.currentTimeMillis());
        String associationArn = null;
        try {
            createRepository(repositoryName);
            CreateConnectionTokenResult connectionToken = fetchConnectionToken();
            verifyRepositoryIsVisible(repositoryName, connectionToken);
            associationArn = onboard(repositoryName, getOwner(), connectionToken.getConnectionToken());
            verifyIsListed(repositoryName, ProviderType.GitHub);
            onboard(repositoryName, getOwner(), connectionToken.getConnectionToken());
        } finally {
            if (associationArn != null) {
                disassociate(associationArn);
            }
            deleteRepository(repositoryName);
        }
    }

    static String getRepositoryName(final String domain, final String region) {
        return getRepositoryName(domain, region, "GHOnboarding");
    }

    private static String getRepositoryNameCMCMK(final String domain, final String region) {
        return getRepositoryName(domain, region, "GHOnboardingCMCMK");
    }

    @BeforeClass(alwaysRun = true)
    public void setup() {
        // We are using same GitHub account for onboarding and inference canaries.
        // Commenting this to avoid cleaning up repos retained during 404 for Inference canaries.
        //cleanupOldRepos();
    }
}
