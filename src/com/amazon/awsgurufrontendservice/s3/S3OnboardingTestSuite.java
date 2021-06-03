package com.amazon.awsgurufrontendservice.s3;

import com.amazonaws.services.gurufrontendservice.model.AssociateRepositoryRequest;
import com.amazonaws.services.gurufrontendservice.model.AssociateRepositoryResult;
import com.amazonaws.services.gurufrontendservice.model.ConflictException;
import com.amazonaws.services.gurufrontendservice.model.KMSKeyDetails;
import com.amazonaws.services.gurufrontendservice.model.ProviderType;
import com.amazonaws.services.gurufrontendservice.model.Repository;
import com.amazonaws.services.gurufrontendservice.model.S3Repository;
import com.amazonaws.services.gurufrontendservice.model.ValidationException;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang.StringUtils;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import java.util.Optional;
import java.util.UUID;

@Log4j2
@Test(groups = {"s3bucket-onboarding", "onboarding"})
public class S3OnboardingTestSuite extends S3TestSuite {

    @Parameters({"domain", "region"})
    public S3OnboardingTestSuite(String domain, String region) {
        super(domain, region);
    }

    @Parameters({"domain", "region"})
    @Test(groups = {"canary", "s3bucket-onboarding-canary"})
    public void onboardS3bucketRepository(final String domain, final String region) throws Exception {
        onboardS3RepositoryTest(domain, region, Optional.empty());
    }


    @Parameters({"domain",  "region"})
    @Test(groups = {"canary", "s3bucket-onboarding-canary"})
    public void onboardS3bucketRepositoryCMCMK(final String domain, final String region) throws Exception {
        String repositoryNameCMCMK = getRepositoryNameCMCMK(domain, region);
        onboardS3RepositoryTest(repositoryNameCMCMK, Optional.of(createKMSKeyDetailsCmCmk()));
    }


    @Parameters({"domain", "region"})
    @Test(groups = {"s3bucket-onboarding", "onboarding"})
    public void associateRepositoryTestS3CmCmk(final String domain, final String region) throws Exception {

        java.util.Optional<KMSKeyDetails> kmsKeyDetails = java.util.Optional.of(createKMSKeyDetailsCmCmk());
        onboardS3RepositoryTest(domain, region, kmsKeyDetails);
    }

    @Parameters({"domain", "region"})
    @Test(groups = {"s3bucket-onboarding", "onboarding"})
    public void associateRepositoryTestS3AoCmk(final String domain, final String region) throws Exception {

        java.util.Optional<KMSKeyDetails> kmsKeyDetails = java.util.Optional.of(createKMSKeyDetailsAoCmk());
        onboardS3RepositoryTest(domain, region, kmsKeyDetails);
    }

    @Parameters({"domain", "region"})
    @Test(expectedExceptions = ValidationException.class)
    public void associateRepositoryTestS3CmCmkWithoutKeyId(final String domain, final String region) throws Exception {
        java.util.Optional<KMSKeyDetails> kmsKeyDetails = java.util.Optional.of(createKMSKeyDetailsCmCmk());
        kmsKeyDetails.get().setKMSKeyId(null);
        onboardS3RepositoryTest(domain, region, kmsKeyDetails);
    }

    @Parameters({"domain", "region"})
    @Test(expectedExceptions = ValidationException.class)
    public void associateRepositoryTestS3AoCmkWithKeyId(final String domain, final String region) throws Exception {
        Optional<KMSKeyDetails> kmsKeyDetails = Optional.of(createKMSKeyDetailsAoCmk());
        kmsKeyDetails.get().setKMSKeyId("FakeId");
        onboardS3RepositoryTest(domain, region, kmsKeyDetails);
    }

    private void onboardS3RepositoryTest(final String domain, final String region,
                                         final Optional<KMSKeyDetails> kmsKeyDetails) throws Exception {
        String repositoryName = getRepositoryName(domain, region);
        onboardS3RepositoryTest(repositoryName, kmsKeyDetails);
    }

    private void onboardS3RepositoryTest(final String repositoryName,
                                         final Optional<KMSKeyDetails> kmsKeyDetails) throws Exception {
        refreshGuruClient();
        String associationArn = null;
        try {
            AssociateRepositoryResult associateRepositoryResult = onboard(repositoryName, kmsKeyDetails);
            associationArn = associateRepositoryResult.getRepositoryAssociation().getAssociationArn();
            verifyIsListed(repositoryName, ProviderType.S3Bucket);
        } finally {
            if (!StringUtils.isEmpty(associationArn )) {
                disassociate(associationArn);
            }
        }
    }

    @Parameters({"domain", "region"})
    @Test(groups = {"s3bucket-onboarding", "onboarding"})
    public void associateRepositoryTestForS3WithBucketName(final String domain, final String region) throws Exception {
        String repositoryName = getRepositoryName(domain, region);
        String associationArn = null;

        try{
            AssociateRepositoryResult associateRepositoryResult = onboard(repositoryName);
            associationArn = associateRepositoryResult.getRepositoryAssociation().getAssociationArn();
            verifyIsListed(repositoryName, ProviderType.S3Bucket);
        } finally {
            if(!StringUtils.isEmpty(associationArn )){
                disassociate(associationArn);
            }
        }
    }

    @Test(expectedExceptions = ValidationException.class)
    public void emptyRepoNameTest() {
        AssociateRepositoryRequest associationsRequest = new AssociateRepositoryRequest().withRepository(
                        new Repository().withS3Bucket(
                                new S3Repository().withName("")));
        getGuruClient().associateRepository(associationsRequest);
    }

    @Test(expectedExceptions = ValidationException.class)
    public void nullRepoNameTest() {
        AssociateRepositoryRequest associationsRequest = new AssociateRepositoryRequest().withRepository(
                new Repository().withS3Bucket(
                        new S3Repository()));
        getGuruClient().associateRepository(associationsRequest);
    }

    @Parameters({"domain", "region"})
    @Test(expectedExceptions = ValidationException.class)
    public void invalidS3BucketNameTest(final String domain, final String region) {
        String repositoryName = getRepositoryName(domain, region);
        AssociateRepositoryRequest associationsRequest = new AssociateRepositoryRequest().withRepository(
                new Repository().withS3Bucket(
                        new S3Repository().withName(repositoryName).withBucketName("test")));
        getGuruClient().associateRepository(associationsRequest);
    }


    @Parameters({"domain"})
    @Test(expectedExceptions = ConflictException.class,
          expectedExceptionsMessageRegExp = "^An association for this repository already exists\\. " +
                  "If the association has failed, " +
                  "disassociate the repository before attempting to associate the repository again\\. (.*)$")
    public void associationAlreadyExistTest(final String domain) throws Exception {
        refreshGuruClient();
        final String repositoryName = String.format("S3bucketOnboardingExistTest-%s-%s", domain, System.currentTimeMillis());
        String associationArn = null;
        try {
            final AssociateRepositoryResult associateRepositoryResult = onboard(repositoryName);
            associationArn = associateRepositoryResult.getRepositoryAssociation().getAssociationArn();
            verifyIsListed(repositoryName, ProviderType.S3Bucket);
            onboard(repositoryName);
        } finally {
            if (StringUtils.isEmpty(associationArn )) {
                disassociate(associationArn);
            }
        }
    }

    private static String getRepositoryName(final String domain, final String region) {
        return getRepositoryName(domain, region, "S3bucketOnboarding");
    }

    private static String getRepositoryNameCMCMK(final String domain, final String region) {
        return getRepositoryName(domain, region, "S3bucketOnboardingCMCMK");
    }
}
