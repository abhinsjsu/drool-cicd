package com.amazon.awsgurufrontendservice.s3;

import com.amazon.awsgurufrontendservice.IntegrationTestBase;
import com.amazonaws.services.gurufrontendservice.model.AssociateRepositoryResult;
import com.amazonaws.services.gurufrontendservice.model.KMSKeyDetails;
import com.amazonaws.services.gurufrontendservice.model.Repository;
import com.amazonaws.services.gurufrontendservice.model.S3Repository;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.GetCallerIdentityRequest;
import lombok.extern.log4j.Log4j2;

import java.util.Optional;

@Log4j2
public class S3TestSuite extends IntegrationTestBase {

    private final String bucketName;

    public S3TestSuite(String domain, String regionName) {
        super(domain, regionName);
        String accountId = AWSSecurityTokenServiceClientBuilder.standard().withRegion(regionName).build()
                                                               .getCallerIdentity(new GetCallerIdentityRequest()).getAccount();
        this.bucketName = AccountIdToS3BucketNameMapper.getS3BucketName(accountId, regionName);
        log.info("Detected domain {} and region {}, auto-resolve existing bucket {} in corresponding FES canary account",
                 domain, regionName, bucketName);
    }

    protected AssociateRepositoryResult onboard(final String repositoryName) throws InterruptedException {
        return onboard(repositoryName, Optional.empty(), bucketName);
    }

    protected AssociateRepositoryResult onboard(final String repositoryName,
                                                final Optional<KMSKeyDetails> kmsKeyDetail) throws InterruptedException {
        return onboard(repositoryName, kmsKeyDetail, bucketName);
    }

    protected AssociateRepositoryResult onboard(final String repositoryName,  final Optional<KMSKeyDetails> kmsKeyDetail, final String bucketName) throws InterruptedException {
        final Repository repository;
        if(kmsKeyDetail.isPresent()) {
            repository = new Repository().withS3Bucket(
                    new S3Repository().withName(repositoryName).withBucketName(bucketName));
            return onboardBase(repositoryName, repository, kmsKeyDetail, null);
        }

        repository = new Repository().withS3Bucket(
                new S3Repository().withName(repositoryName).withBucketName(bucketName));
        return onboardBase(repositoryName, repository, null);

    }
}
