package com.amazon.awsgurufrontendservice.bitbucket;

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
@Test(groups = {"bitbucket-onboarding", "onboarding"})
public class BitbucketOnboardingTestSuite extends BitbucketTestSuite {

    @Parameters({"domain", "region", "bitbucketSecretId"})
    public BitbucketOnboardingTestSuite(final String domain,
                                        final String region,
                                        final String bitbucketSecretId) {
        super(domain, region, bitbucketSecretId);
    }

    @Parameters({"domain", "region"})
    @Test(groups = {"canary", "bitbucket-onboarding-canary"})
    public void onboardBitbucketRepositoryCMCMK(final String domain,
                                                final String region) throws InterruptedException {

        String repositoryNameCMCMK = getRepositoryNameCMCMK(domain, region);
        onboardBitbucketRepositoryTest(repositoryNameCMCMK, Optional.of(createKMSKeyDetailsCmCmk()));
    }

    @Parameters({"domain", "region"})
    @Test(groups = {"bitbucket-onboarding", "onboarding"})
    public void associateRepositoryTestBitbucketCmCmk(final String domain,
                                           final String region) throws InterruptedException {
        Optional<KMSKeyDetails> kmsKeyDetails = Optional.of(createKMSKeyDetailsCmCmk());
        onboardBitbucketRepositoryTest(domain, region, kmsKeyDetails);
    }

    @Parameters({"domain", "region"})
    @Test(groups = {"bitbucket-onboarding", "onboarding"})
    public void associateRepositoryTestBitbucketAoCmk(final String domain,
                                             final String region) throws InterruptedException {
        Optional<KMSKeyDetails> kmsKeyDetails = Optional.of(createKMSKeyDetailsAoCmk());
        onboardBitbucketRepositoryTest(domain, region, kmsKeyDetails);
    }

    @Parameters({"domain", "region"})
    @Test(expectedExceptions = ValidationException.class)
    public void associateRepositoryTestBitbucketAoCmkWithKeyId(final String domain,
                                                      final String region) throws InterruptedException {
        Optional<KMSKeyDetails> kmsKeyDetails = Optional.of(createKMSKeyDetailsAoCmk());
        kmsKeyDetails.get().setKMSKeyId("fakeKeyId");
        onboardBitbucketRepositoryTest(domain, region, kmsKeyDetails);
    }

    @Parameters({"domain", "region"})
    @Test(expectedExceptions = ValidationException.class)
    public void associateRepositoryTestBitbucketCmCmkWithoutKeyId(final String domain,
                                                               final String region) throws InterruptedException {
        Optional<KMSKeyDetails> kmsKeyDetails = Optional.of(createKMSKeyDetailsCmCmk());
        kmsKeyDetails.get().setKMSKeyId(null);
        onboardBitbucketRepositoryTest(domain, region, kmsKeyDetails);
    }

    private void onboardBitbucketRepositoryTest(final String domain, final String region,
                                                final Optional<KMSKeyDetails> kmsKeyDetails) throws InterruptedException {
        String repositoryName = getRepositoryName(domain, region);
        onboardBitbucketRepositoryTest(repositoryName, kmsKeyDetails);
    }

    private void onboardBitbucketRepositoryTest(final String repositoryName,
                                                final Optional<KMSKeyDetails> kmsKeyDetails) throws InterruptedException {
        refreshGuruClient();

        String associationArn = null;

        createRepository(repositoryName);
        this.codeConnectHelper.waitForRepositoryToBeAvailable(repositoryName, getUsername(),
            credential.getConnectionArn());

        try {
            final AssociateRepositoryResult associateRepositoryResult = onboard(repositoryName, getUsername(), kmsKeyDetails);
            associationArn = associateRepositoryResult.getRepositoryAssociation().getAssociationArn();
            verifyIsListed(repositoryName, ProviderType.Bitbucket);
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
        associationsRequest.setRepository(new Repository().withBitbucket(
                new ThirdPartySourceRepository()
                        .withName("")
                        .withConnectionArn(credential.getConnectionArn())
                        .withOwner(credential.getUsername())));
        getGuruClient().associateRepository(associationsRequest);
    }

    @Test(expectedExceptions = ValidationException.class)
    public void nullRepoNameTest() {
        AssociateRepositoryRequest associationsRequest = new AssociateRepositoryRequest();
        associationsRequest.setRepository(new Repository().withBitbucket(
                new ThirdPartySourceRepository()
                        .withOwner(credential.getUsername())
                        .withConnectionArn(credential.getConnectionArn())));
        getGuruClient().associateRepository(associationsRequest);
    }

    @Parameters({"domain", "region"})
    @Test(expectedExceptions = ConflictException.class,
            expectedExceptionsMessageRegExp = "^An association for this repository already exists\\. If the association has failed, " +
                    "disassociate the repository before attempting to associate the repository again\\. (.*)$")
    public void associationAlreadyExistTest(final String domain,
                                            final String region) throws InterruptedException {
        refreshGuruClient();
        String repositoryName = getRepositoryName(domain, region);
        String associationArn = null;
        try {
            createRepository(repositoryName);
            final AssociateRepositoryResult associateRepositoryResult = onboard(repositoryName, getUsername());
            associationArn = associateRepositoryResult.getRepositoryAssociation().getAssociationArn();
            verifyIsListed(repositoryName, ProviderType.Bitbucket);
            onboard(repositoryName, getUsername());
        } finally {
            if (associationArn != null) {
                disassociate(associationArn);
                deleteRepository(repositoryName);
            }
        }
    }

    private static String getRepositoryName(final String domain, final String region) {
        return getRepositoryName(domain, region, "BBOnboarding");
    }

    private static String getRepositoryNameCMCMK(final String domain, final String region) {
        return getRepositoryName(domain, region, "BBOnboardingCMCMK");
    }
}
