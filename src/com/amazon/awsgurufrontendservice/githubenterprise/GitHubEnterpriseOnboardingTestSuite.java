package com.amazon.awsgurufrontendservice.githubenterprise;

import com.amazonaws.services.gurufrontendservice.model.AssociateRepositoryResult;
import com.amazonaws.services.gurufrontendservice.model.KMSKeyDetails;
import com.amazonaws.services.gurufrontendservice.model.ProviderType;
import com.amazonaws.services.gurufrontendservice.model.AssociateRepositoryRequest;
import com.amazonaws.services.gurufrontendservice.model.ConflictException;
import com.amazonaws.services.gurufrontendservice.model.ThirdPartySourceRepository;
import com.amazonaws.services.gurufrontendservice.model.Repository;
import com.amazonaws.services.gurufrontendservice.model.ValidationException;

import lombok.extern.log4j.Log4j2;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import java.util.Optional;

@Log4j2
@Test(groups = {"githubenterprise-onboarding", "onboarding"})
public class GitHubEnterpriseOnboardingTestSuite extends GitHubEnterpriseTestSuite {
    @Parameters({"domain", "region", "gitHubEnterpriseSecretId"})
    public GitHubEnterpriseOnboardingTestSuite(final String domain,
                                               final String region,
                                               final String gitHubEnterpriseSecretId) {
        super(domain, region, gitHubEnterpriseSecretId);
    }

    @Parameters({"domain", "region"})
    @Test(groups = {"canary", "githubenterprise-onboarding-canary"})
    public void onboardGitHubEnterpriseRepository(final String domain, final String region) throws Exception {
        onboardGHERepositoryTest(domain, region, Optional.empty());
    }

    @Parameters({"domain", "region"})
    @Test(groups = {"canary", "githubenterprise-onboarding-canary"})
    public void onboardGitHubEnterpriseRepositoryCMCMK(final String domain, final String region) throws Exception {
        String repositoryNameCMCMK = getRepositoryNameCMCMK(domain, region);
        onboardGHERepositoryTest(repositoryNameCMCMK, Optional.of(createKMSKeyDetailsCmCmk()));
    }

    @Parameters({"domain", "region"})
    @Test(groups = {"githubenterprise-onboarding", "onboarding"})
    public void associateRepositoryTestGitHubEnterpriseCmCmk(final String domain, final String region) throws Exception {
        Optional<KMSKeyDetails> kmsKeyDetails = Optional.of(createKMSKeyDetailsCmCmk());
        onboardGHERepositoryTest(domain, region, kmsKeyDetails);
    }

    @Parameters({"domain", "region"})
    @Test(groups = {"githubenterprise-onboarding", "onboarding"})
    public void associateRepositoryTestGitHubEnterpriseAoCmk(final String domain, final String region) throws Exception {
        Optional<KMSKeyDetails> kmsKeyDetails = Optional.of(createKMSKeyDetailsAoCmk());
        onboardGHERepositoryTest(domain, region, kmsKeyDetails);
    }

    @Parameters({"domain", "region"})
    @Test(expectedExceptions = ValidationException.class)
    public void associateRepositoryTestGitHubEnterpriseCmCmkWithoutKeyId(final String domain, final String region) throws Exception {
        Optional<KMSKeyDetails> kmsKeyDetails = Optional.of(createKMSKeyDetailsCmCmk());
        kmsKeyDetails.get().setKMSKeyId(null);
        onboardGHERepositoryTest(domain, region, kmsKeyDetails);
    }

    @Parameters({"domain", "region"})
    @Test(expectedExceptions = ValidationException.class)
    public void associateRepositoryTestGitHubEnterpriseAoCmkWithKeyId(final String domain, final String region) throws Exception {
        Optional<KMSKeyDetails> kmsKeyDetails = Optional.of(createKMSKeyDetailsAoCmk());
        kmsKeyDetails.get().setKMSKeyId("fakeKeyId");
        onboardGHERepositoryTest(domain, region, kmsKeyDetails);
    }

    private void onboardGHERepositoryTest(final String domain, final String region,
                                          final Optional<KMSKeyDetails> kmsKeyDetails) throws Exception {
        String repositoryName = getRepositoryName(domain, region);
        onboardGHERepositoryTest(repositoryName, kmsKeyDetails);
    }

    private void onboardGHERepositoryTest(final String repositoryName,
                                          final Optional<KMSKeyDetails> kmsKeyDetails) throws Exception {

        refreshGuruClient();
        String associationArn = null;
        createRepository(repositoryName);

        try {
            final AssociateRepositoryResult associateRepositoryResult = onboard(repositoryName, getOwner(), kmsKeyDetails);
            associationArn = associateRepositoryResult.getRepositoryAssociation().getAssociationArn();
            verifyIsListed(repositoryName, ProviderType.GitHubEnterpriseServer);
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
        associationsRequest.setRepository(new Repository().withGitHubEnterpriseServer(
                new ThirdPartySourceRepository()
                        .withName("")
                        .withConnectionArn(credential.getConnectionArn())
                        .withOwner(credential.getOwner())));
        getGuruClient().associateRepository(associationsRequest);
    }

    @Test(expectedExceptions = ValidationException.class)
    public void nullRepoNameTest() {
        AssociateRepositoryRequest associationsRequest = new AssociateRepositoryRequest();
        associationsRequest.setRepository(new Repository().withGitHubEnterpriseServer(
                new ThirdPartySourceRepository()
                        .withOwner(credential.getOwner())
                        .withConnectionArn(credential.getConnectionArn())));
        getGuruClient().associateRepository(associationsRequest);
    }

    @Parameters({"domain"})
    @Test(expectedExceptions = ConflictException.class,
            expectedExceptionsMessageRegExp = "^An association for this repository already exists\\. If the association has failed, " +
                    "disassociate the repository before attempting to associate the repository again\\. (.*)$")
    public void associationAlreadyExistTest(final String domain) throws Exception {
        refreshGuruClient();
        String repositoryName = String.format("GHEOnboarding%s%d", domain, System.currentTimeMillis());
        String associationArn = null;
        createRepository(repositoryName);
        try {
            final AssociateRepositoryResult associateRepositoryResult = onboard(repositoryName, getOwner());
            associationArn = associateRepositoryResult.getRepositoryAssociation().getAssociationArn();
            verifyIsListed(repositoryName, ProviderType.GitHubEnterpriseServer);
            onboard(repositoryName, getOwner());
        } finally {
            if (associationArn != null) {
                disassociate(associationArn);
            }
            deleteRepository(repositoryName);
        }
    }

    private static String getRepositoryName(final String domain, final String region) {
        return getRepositoryName(domain, region, "GHEOnboarding");
    }

    private static String getRepositoryNameCMCMK(final String domain, final String region) {
        return getRepositoryName(domain, region, "GHEOnboardingCMCMK");
    }

}
