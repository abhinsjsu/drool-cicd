package com.amazon.awsgurufrontendservice;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.STSAssumeRoleSessionCredentialsProvider;
import com.amazonaws.services.gurufrontendservice.model.AWSGuruFrontendServiceException;
import com.amazonaws.services.gurufrontendservice.model.AssociateRepositoryRequest;
import com.amazonaws.services.gurufrontendservice.model.CodeCommitRepository;
import com.amazonaws.services.gurufrontendservice.model.ConflictException;
import com.amazonaws.services.gurufrontendservice.model.GitHubRepository;
import com.amazonaws.services.gurufrontendservice.model.KMSKeyDetails;
import com.amazonaws.services.gurufrontendservice.model.ProviderType;
import com.amazonaws.services.gurufrontendservice.model.Repository;
import com.amazonaws.services.gurufrontendservice.model.ValidationException;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagement;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClientBuilder;
import com.amazonaws.services.identitymanagement.model.GetRoleRequest;
import com.amazonaws.services.identitymanagement.model.GetRoleResult;
import com.google.common.collect.ImmutableMap;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.Optional;

import static org.testng.Assert.assertEquals;

@Log4j2
@Test(groups = {"codecommit-onboarding", "onboarding"})
public class CodeCommmitOnboardingTestSuite extends CodeCommitTestSuite {

    public static final Map<String, String> CORS_HEADERS = ImmutableMap.of(
        "Access-Control-Allow-Origin", "*",
        "Access-Control-Expose-Headers", "x-amzn-errortype,x-amzn-requestid,x-amz-apigw-id,x-amzn-trace-id",
        "Access-Control-Allow-Methods", "*",
        "Access-Control-Allow-Headers", "*");

    private final AmazonIdentityManagement iam;

    @Parameters({"domain", "region"})
    public CodeCommmitOnboardingTestSuite(final String domain,
                                          final String region) {
        super(domain, region);
        this.iam = AmazonIdentityManagementClientBuilder.defaultClient();
    }

    @Parameters({"domain", "region"})
    @Test(groups = {"canary", "codecommit-onboarding-canary"})
    public void onboardCodeCommitRepository(final String domain, final String region) throws InterruptedException {
        onboardCodeCommitRepositoryTest(domain, region, Optional.empty());
    }

    @Parameters({"domain", "region"})
    @Test(groups = {"canary", "codecommit-onboarding-canary"})
    public void onboardCodeCommitRepositoryCMCMK(final String domain, final String region) throws InterruptedException {
        String repositoryNameCMCMK = getRepositoryNameCMCMK(domain, region);
        onboardCodeCommitRepositoryTest(repositoryNameCMCMK, Optional.of(createKMSKeyDetailsCmCmk()));
    }


    @Parameters({"domain", "region"})
    @Test(groups = {"codecommit-onboarding", "onboarding"})
    public void associateRepositoryTestCodeCommitCmCmk(final String domain, final String region) throws InterruptedException {

        Optional<KMSKeyDetails> kmsKeyDetails = Optional.of(createKMSKeyDetailsCmCmk());
        onboardCodeCommitRepositoryTest(domain, region, kmsKeyDetails);
    }

    @Parameters({"domain", "region"})
    @Test(groups = {"codecommit-onboarding", "onboarding"})
    public void associateRepositoryTestCodeCommitAoCmk(final String domain, final String region) throws InterruptedException {

        Optional<KMSKeyDetails> kmsKeyDetails = Optional.of(createKMSKeyDetailsAoCmk());
        onboardCodeCommitRepositoryTest(domain, region, kmsKeyDetails);
    }

    @Parameters({"domain", "region"})
    @Test(expectedExceptions = ValidationException.class)
    public void associateRepositoryTestCodeCommitCmCmkWithoutKeyId(final String domain, final String region) throws InterruptedException {

        Optional<KMSKeyDetails> kmsKeyDetails = Optional.of(createKMSKeyDetailsCmCmk());
        kmsKeyDetails.get().setKMSKeyId(null);
        onboardCodeCommitRepositoryTest(domain, region, kmsKeyDetails);
    }

    @Parameters({"domain", "region"})
    @Test(expectedExceptions = ValidationException.class)
    public void associateRepositoryTestCodeCommitAoCmkWithKeyId(final String domain, final String region) throws InterruptedException {
        Optional<KMSKeyDetails> kmsKeyDetails = Optional.of(createKMSKeyDetailsAoCmk());
        kmsKeyDetails.get().setKMSKeyId("fakeKeyId");
        onboardCodeCommitRepositoryTest(domain, region, kmsKeyDetails);
    }

    private void onboardCodeCommitRepositoryTest(final String domain,
                                                 final String region,
                                                 final Optional<KMSKeyDetails> kmsKeyDetails) throws InterruptedException {
        String repositoryName = getRepositoryName(domain, region);
        onboardCodeCommitRepositoryTest(repositoryName, kmsKeyDetails);
    }

    private void onboardCodeCommitRepositoryTest(final String repositoryName,
                                                 final Optional<KMSKeyDetails> kmsKeyDetails) throws InterruptedException {

        refreshGuruClient();
        createRepository(repositoryName);
        String associationArn = null;
        try {
            associationArn = onboard(repositoryName, kmsKeyDetails, null);
            verifyIsListed(repositoryName, ProviderType.CodeCommit);
        } finally {
            if (associationArn != null) {
                disassociate(associationArn);
            }
            deleteRepository(repositoryName);
        }
    }

    @Test(expectedExceptions = ValidationException.class)
    public void emptyRepoNameTest() {
        AssociateRepositoryRequest associationsRequest = new AssociateRepositoryRequest();
        associationsRequest.setRepository(new Repository().withCodeCommit(
            new CodeCommitRepository().withName("")));
        getGuruClient().associateRepository(associationsRequest);
    }

    @Test(expectedExceptions = ValidationException.class)
    public void nullRepoNameTest() {
        AssociateRepositoryRequest associationsRequest = new AssociateRepositoryRequest();
        associationsRequest.setRepository(new Repository().withCodeCommit(
            new CodeCommitRepository()));
        getGuruClient().associateRepository(associationsRequest);
    }

    @Test(expectedExceptions = ValidationException.class)
    public void multipleRepositoryObjectTest() {
        AssociateRepositoryRequest associationsRequest = new AssociateRepositoryRequest();
        associationsRequest.setRepository(new Repository().withCodeCommit(
            new CodeCommitRepository().withName("Repo")).withGitHub(new GitHubRepository()
            .withName("Foo")
            .withAccessToken("2123133113131")
            .withOwner("bar")));
        getGuruClient().associateRepository(associationsRequest);
    }

    @Parameters({"domain"})
    @Test(expectedExceptions = ConflictException.class,
            expectedExceptionsMessageRegExp = "^An association for this repository already exists\\. If the association has failed, " +
                    "disassociate the repository before attempting to associate the repository again\\. (.*)$")
    public void associationAlreadyExistTest(final String domain) throws InterruptedException {
        String repositoryName = String.format("CCOnboarding%s%d", domain, System.currentTimeMillis());
        createRepository(repositoryName);
        String associationArn = null;
        try {
            associationArn = onboard(repositoryName, null);
            verifyIsListed(repositoryName, ProviderType.CodeCommit);
            onboard(repositoryName, null); // the second associate attempt should fail with a ConflictException
        } finally {
            if (associationArn != null) {
                disassociate(associationArn);
            }
            deleteRepository(repositoryName);
        }
    }

    @Test(groups = "CORS")
    @Parameters({"region"})
    public void corsHeadersTest(final String region) {
        GetRoleRequest getRoleRequest = new GetRoleRequest().withRoleName("InsufficientPermissionRole-" + region);
        GetRoleResult getRoleResponse = iam.getRole(getRoleRequest);
        AWSCredentialsProvider insufficientRoleCreds =
            new STSAssumeRoleSessionCredentialsProvider
                .Builder(getRoleResponse.getRole().getArn(), "CorsIntegTest").build();
        AssociateRepositoryRequest associationsRequest =
            new AssociateRepositoryRequest().withRequestCredentialsProvider(insufficientRoleCreds);
        associationsRequest.setRepository(new Repository().withCodeCommit(
            new CodeCommitRepository().withName("")));
        associationsRequest.putCustomRequestHeader("Origin", "foo.bar.com");

        try {
            getGuruClient().associateRepository(associationsRequest);
        } catch (AWSGuruFrontendServiceException e) {
            Map<String, String> responseHeaders = e.getHttpHeaders();

            log.info(responseHeaders);

            assertEquals(CORS_HEADERS.entrySet().stream().filter(entrySet ->
                !StringUtils.equals(responseHeaders.get(entrySet.getKey()), entrySet.getValue())).count(), 0);
        }
    }

    private static String getRepositoryName(final String domain, final String region) {
        return getRepositoryName(domain, region, "CCOnboarding");
    }

    private static String getRepositoryNameCMCMK(final String domain, final String region) {
        return getRepositoryName(domain, region, "CCOnboardingCMCMK");
    }

    @BeforeClass(alwaysRun = true)
    public void setup() {
        cleanupOldRepos();
    }
}
