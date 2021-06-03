package com.amazon.awsgurufrontendservice.s3;

import com.amazon.awsgurufrontendservice.IntegrationTestBase;
import com.amazon.awsgurufrontendservice.PathResolver;
import com.amazonaws.services.gurufrontendservice.model.AnalysisType;
import com.amazonaws.services.gurufrontendservice.model.CodeArtifacts;
import com.amazonaws.services.gurufrontendservice.model.CodeReview;
import com.amazonaws.services.gurufrontendservice.model.CodeReviewType;
import com.amazonaws.services.gurufrontendservice.model.CommitDiffSourceCodeType;
import com.amazonaws.services.gurufrontendservice.model.ConflictException;
import com.amazonaws.services.gurufrontendservice.model.CreateCodeReviewRequest;
import com.amazonaws.services.gurufrontendservice.model.CreateCodeReviewResult;
import com.amazonaws.services.gurufrontendservice.model.DescribeRepositoryAssociationResult;
import com.amazonaws.services.gurufrontendservice.model.EventType;
import com.amazonaws.services.gurufrontendservice.model.KMSKeyDetails;
import com.amazonaws.services.gurufrontendservice.model.RecommendationSummary;
import com.amazonaws.services.gurufrontendservice.model.Repository;
import com.amazonaws.services.gurufrontendservice.model.RepositoryAnalysis;
import com.amazonaws.services.gurufrontendservice.model.RequestMetadata;
import com.amazonaws.services.gurufrontendservice.model.S3BucketRepository;
import com.amazonaws.services.gurufrontendservice.model.S3Repository;
import com.amazonaws.services.gurufrontendservice.model.S3RepositoryDetails;
import com.amazonaws.services.gurufrontendservice.model.SourceCodeType;
import com.amazonaws.services.gurumetadataservice.model.VendorName;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.GetCallerIdentityRequest;
import lombok.extern.log4j.Log4j2;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import java.io.File;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

@Log4j2
@Test(groups = {"inference-integ"})
public class CreateCodeReviewS3TestSuite extends IntegrationTestBase {

    private static final String CODE_ARTIFACTS_PATH = "tst-resource";
    private static final String SOURCE_CODE_KEY = "AWSGuruDragonglassTestData.zip";
    private static final String BUILD_JAR_KEY = "AWSGuruDragonglassTestDataBinary.zip";
    private static final String SOURCE_CODE_S3_KEY = UUID.randomUUID().toString() + "/AWSGuruDragonglassTestData.zip";
    private static final String BUILD_JAR_S3_KEY = UUID.randomUUID().toString() + "/AWSGuruDragonglassTestDataBinary.zip";
    private static final String SOURCE_CODE_GIT_DIFF_SOURCE_COMMIT_ID = "209ec0e83a1b6cb4b1e1a6d548e29fd3bc5ea902";
    private static final String SOURCE_CODE_GIT_DIFF_DEST_COMMIT_ID = "4822634cfc1423ef565a2ce09975d47787755688";
    private static final String OWNER = "S3CanaryPullRequestTest";
    private static final String PULL_REQUEST_ID = "1";
    private static final String REPO_NAME_FORMAT = "S3CanaryRepo-%s-%s";
    private static final String CMCMK_REPO_NAME_FORMAT = "S3CMCMKCanaryRepo-%s-%s";
    private static final String SECURITY_RECOMMENDATION_PREFIX = "security-";
    private static final String S3_TEST_NAME = "S3TestSuite";
    private static final String S3_TEST_NAME_CMCMK = "S3TestSuiteCMCMK";

    private final PathResolver pathResolver;
    private final String region;
    private final String domain;
    private final String accountId;
    private final String repositoryName;
    private final String repositoryNameCMCMK;
    private final String bucketName;
    private String repoAssociationArn;
    private String repoAssociationArnCMCMK;
    private AmazonS3 amazonS3;

    @Parameters({"domain", "region"})
    public CreateCodeReviewS3TestSuite(final String domain, final String region) {
        super(domain, region);
        this.region = region;
        this.domain = domain;
        this.accountId = AWSSecurityTokenServiceClientBuilder.standard().withRegion(region).build()
                .getCallerIdentity(new GetCallerIdentityRequest()).getAccount();
        this.pathResolver = new PathResolver();
        this.repositoryName = getRepoName(domain, region);
        this.repositoryNameCMCMK = getRepoNameCMCMK(domain, region);
        this.bucketName = AccountIdToS3BucketNameMapper.getS3BucketName(accountId, this.region);
        log.info("Detected domain {} and region {}, auto-resolve existing bucket {} in corresponding FES canary account",
                 domain, region, bucketName);

        amazonS3 = createAmazonS3Client(region);
    }

    @BeforeClass(alwaysRun = true)
    public void setupRepositoryForTesting() throws InterruptedException {
        Repository repository = new Repository().withS3Bucket(
                new S3Repository()
                        .withName(repositoryName)
                        .withBucketName(bucketName));

        log.info("Associating repository {} to S3 bucket {}", repositoryName, bucketName);
        associateRepository(repository);

        uploadArtifacts(bucketName);

        Repository repositoryCMCMK = new Repository().withS3Bucket(
                new S3Repository()
                        .withName(repositoryNameCMCMK)
                        .withBucketName(bucketName));

        log.info("Associating repository with CMCMK {} to S3 bucket {}", repositoryNameCMCMK, bucketName);
        associateRepository(repositoryCMCMK, Optional.of(createKMSKeyDetailsCmCmk()));
    }

    @Test(groups = {"canary", "s3-repositoryanalysis-engine-driver-canary"})
    public void createCodeReviewS3TestBothCodeQualityAndSecurity() throws Exception {
        createBothQualityAndSecurityAndVerify(this.repositoryName, this.repoAssociationArn);
    }

    @Test(groups = {"canary", "s3-repositoryanalysis-engine-driver-canary"})
    public void createCodeReviewS3TestBothCodeQualityAndSecurityCMCMK() throws Exception {
        createBothQualityAndSecurityAndVerify(this.repositoryNameCMCMK, this.repoAssociationArnCMCMK);
    }

    @Test(groups = {"canary", "s3-repositoryanalysis-engine-driver-canary"})
    public void createCodeReviewS3TestSecurity() throws Exception {
        CreateCodeReviewRequest createCodeReviewRequest = generateCodeReviewRequest(
                Arrays.asList(AnalysisType.Security.toString()), "S3CanaryRepo-Security-");
        CreateCodeReviewResult codeReview = getGuruClient().createCodeReview(createCodeReviewRequest);
        log.info("Created CodeReview {}", codeReview);
        waitAndGetCodeReview(codeReview.getCodeReview().getCodeReviewArn());
        List<RecommendationSummary> recommendationSummaries = waitForCodeGuruReviewerRecommendations(repositoryName,
                codeReview.getCodeReview().getCodeReviewArn(), false);
        verifyVendorName(codeReview.getCodeReview(), VendorName.NativeS3.name());
        // verify only security recommendations are generated
        verifyGeneratedRecommendations(true, false, recommendationSummaries,
                codeReview.getCodeReview().getCodeReviewArn());
    }

    @Test(groups = {"canary", "s3-repositoryanalysis-engine-driver-canary"})
    public void createCodeReviewS3TestCodeQuality() throws Exception {
        CreateCodeReviewRequest createCodeReviewRequest = generateCodeReviewRequest(
                Arrays.asList(AnalysisType.CodeQuality.toString()), "S3CanaryRepo-CodeQuality-");
        CreateCodeReviewResult codeReview = getGuruClient().createCodeReview(createCodeReviewRequest);
        log.info("Created CodeReview {}", codeReview);
        waitAndGetCodeReview(codeReview.getCodeReview().getCodeReviewArn());
        List<RecommendationSummary> recommendationSummaries = waitForCodeGuruReviewerRecommendations(repositoryName,
                codeReview.getCodeReview().getCodeReviewArn(), false);
        verifyVendorName(codeReview.getCodeReview(), VendorName.NativeS3.name());
        // verify only code quality recommendations are generated
        verifyGeneratedRecommendations(false, true, recommendationSummaries,
                codeReview.getCodeReview().getCodeReviewArn());
    }

    @Test(groups = {"canary", "s3-pullrequest-engine-driver-canary"})
    public void createCodeReviewS3PullRequestTest() throws Exception {
        CreateCodeReviewRequest createCodeReviewRequest = generateCodeReviewRequestPullRequest(
                Arrays.asList(AnalysisType.CodeQuality.toString(), AnalysisType.Security.toString()),
                "S3CanaryPullRequest-", Optional.empty());
        CreateCodeReviewResult codeReview = getGuruClient().createCodeReview(createCodeReviewRequest);
        log.info("Created CodeReview {}", codeReview);
        waitAndGetCodeReview(codeReview.getCodeReview().getCodeReviewArn());
        List<RecommendationSummary> recommendationSummaries = waitForCodeGuruReviewerRecommendations(repositoryName,
                codeReview.getCodeReview().getCodeReviewArn(), false);
        verifyVendorName(codeReview.getCodeReview(), VendorName.NativeS3.name());
        // Verify that a code quality recommendation exists on the changed lines
        // Verify that security recommendations exist for the entire repository
        verifyGeneratedRecommendations(true, true, recommendationSummaries,
                codeReview.getCodeReview().getCodeReviewArn());
    }

    @Test(groups = {"canary", "s3-pullrequest-engine-driver-canary"})
    public void createCodeReviewS3GitHubPullRequestTest() throws Exception {
        CreateCodeReviewRequest createCodeReviewRequest = generateCodeReviewRequestPullRequest(
                Arrays.asList(AnalysisType.CodeQuality.toString(), AnalysisType.Security.toString()),
                "S3CanaryPullRequest-GitHubVendor-", Optional.of(VendorName.GitHub.name()));

        CreateCodeReviewResult codeReview = getGuruClient().createCodeReview(createCodeReviewRequest);
        log.info("Created CodeReview {}", codeReview);
        waitAndGetCodeReview(codeReview.getCodeReview().getCodeReviewArn());
        List<RecommendationSummary> recommendationSummaries = waitForCodeGuruReviewerRecommendations(repositoryName,
                                                                                                     codeReview.getCodeReview().getCodeReviewArn(), false);
        verifyVendorName(codeReview.getCodeReview(), VendorName.GitHub.name());
        // Verify that a code quality recommendation exists on the changed lines
        // Verify that security recommendations exist for the entire repository
        verifyGeneratedRecommendations(true, true, recommendationSummaries,
                                       codeReview.getCodeReview().getCodeReviewArn());
    }

    private void verifyVendorName(CodeReview codeReview, String expectedVendor) {
        if (codeReview.getSourceCodeType() == null || codeReview.getSourceCodeType().getRequestMetadata() == null) {
            return;
        }
        String vendorName = codeReview.getSourceCodeType().getRequestMetadata().getVendorName();
        assertEquals(vendorName, expectedVendor);
    }

    private void createBothQualityAndSecurityAndVerify(String repositoryName, String repoAssociationArn) throws Exception {
        CreateCodeReviewRequest createCodeReviewRequest = generateCodeReviewRequest(
                Arrays.asList(AnalysisType.CodeQuality.toString(), AnalysisType.Security.toString()),
                "S3CanaryRepo-Both-", repositoryName, repoAssociationArn);
        CreateCodeReviewResult codeReview = getGuruClient().createCodeReview(createCodeReviewRequest);
        log.info("Created CodeReview {}", codeReview);
        waitAndGetCodeReview(codeReview.getCodeReview().getCodeReviewArn());
        List<RecommendationSummary> recommendationSummaries = waitForCodeGuruReviewerRecommendations(repositoryName,
                codeReview.getCodeReview().getCodeReviewArn(), false);
        // verify both security and code quality recommendations are generated
        verifyGeneratedRecommendations(true, true, recommendationSummaries,
                codeReview.getCodeReview().getCodeReviewArn());
    }

    private CreateCodeReviewRequest generateCodeReviewRequest(final List<String> analysisTypes,
                                                              final String jobNamePrefix) {
        return generateCodeReviewRequest(analysisTypes, jobNamePrefix, repositoryName, repoAssociationArn);
    }

    private CreateCodeReviewRequest generateCodeReviewRequest(final List<String> analysisTypes,
                                                              final String jobNamePrefix,
                                                              final String repositoryName,
                                                              final String repoAssociationArn) {
        S3RepositoryDetails repositoryDetails = new S3RepositoryDetails()
                .withBucketName(bucketName)
                .withCodeArtifacts(new CodeArtifacts()
                        .withSourceCodeArtifactsObjectKey(SOURCE_CODE_S3_KEY)
                        .withBuildArtifactsObjectKey(BUILD_JAR_S3_KEY));
        S3BucketRepository s3BucketRepository = new S3BucketRepository()
                .withName(repositoryName)
                .withDetails(repositoryDetails);
        RepositoryAnalysis repositoryAnalysis = new RepositoryAnalysis()
                .withS3BucketRepository(s3BucketRepository);
        CodeReviewType codeReviewType = new CodeReviewType()
                .withAnalysisTypes(analysisTypes)
                .withRepositoryAnalysis(repositoryAnalysis);
        return new CreateCodeReviewRequest()
                .withName(jobNamePrefix + UUID.randomUUID().toString())
                .withRepositoryAssociationArn(repoAssociationArn)
                .withType(codeReviewType);
    }

    private CreateCodeReviewRequest generateCodeReviewRequestPullRequest(final List<String> analysisTypes,
                                                                         final String jobNamePrefix,
                                                                         final Optional<String> vendorName) {
        S3RepositoryDetails repositoryDetails = new S3RepositoryDetails()
                .withBucketName(bucketName)
                .withCodeArtifacts(new CodeArtifacts()
                        .withSourceCodeArtifactsObjectKey(SOURCE_CODE_S3_KEY)
                        .withBuildArtifactsObjectKey(BUILD_JAR_S3_KEY));
        S3BucketRepository s3BucketRepository = new S3BucketRepository()
                .withName(repositoryName)
                .withDetails(repositoryDetails);
        CommitDiffSourceCodeType commitDiffSourceCodeType = new CommitDiffSourceCodeType()
                .withSourceCommit(SOURCE_CODE_GIT_DIFF_SOURCE_COMMIT_ID)
                .withDestinationCommit(SOURCE_CODE_GIT_DIFF_DEST_COMMIT_ID);
        SourceCodeType sourceCodeType = new SourceCodeType()
                .withCommitDiff(commitDiffSourceCodeType)
                .withS3BucketRepository(s3BucketRepository)
                .withRequestMetadata(new RequestMetadata()
                        .withRequestId(PULL_REQUEST_ID)
                        .withRequester(OWNER)
                        .withEventType(new EventType().withName("pull_request"))
                        .withVendorName(vendorName.orElse(null)));
        RepositoryAnalysis repositoryAnalysis = new RepositoryAnalysis()
                .withSourceCodeType(sourceCodeType);
        CodeReviewType codeReviewType = new CodeReviewType()
                .withAnalysisTypes(analysisTypes)
                .withRepositoryAnalysis(repositoryAnalysis);
        return new CreateCodeReviewRequest()
                .withName(jobNamePrefix + UUID.randomUUID().toString())
                .withRepositoryAssociationArn(repoAssociationArn)
                .withType(codeReviewType);
    }


    private void uploadArtifacts(final String bucketName) {

        File sourceCodeObjectFile = Paths.get(pathResolver.getRealPath(CODE_ARTIFACTS_PATH),
                SOURCE_CODE_KEY).toFile();
        amazonS3.putObject(bucketName, SOURCE_CODE_S3_KEY, sourceCodeObjectFile);

        File binaryObjectFile = Paths.get(pathResolver.getRealPath(CODE_ARTIFACTS_PATH),
                BUILD_JAR_KEY).toFile();
        amazonS3.putObject(bucketName, BUILD_JAR_S3_KEY, binaryObjectFile);
    }

    private String getRepoName(final String stage, final String region) {
        return String.format(REPO_NAME_FORMAT, stage, region);
    }

    private String getRepoNameCMCMK(final String stage, final String region) {
        return String.format(CMCMK_REPO_NAME_FORMAT, stage, region);
    }

    private DescribeRepositoryAssociationResult associateRepository(final Repository repository)
            throws InterruptedException {
        return associateRepository(repository, Optional.empty());
    }

    private DescribeRepositoryAssociationResult associateRepository(final Repository repository, final Optional<KMSKeyDetails> keyDetails)
            throws InterruptedException {
        String repositoryName = repository.getS3Bucket().getName();
        try {
            onboardBase(repositoryName, repository, keyDetails, null);
        } catch (ConflictException exception) {
            log.info("Repository is already associated. {}", repositoryName);
        }
        log.info("Associated repository successfully {}", repositoryName);

        DescribeRepositoryAssociationResult associationResult;
        if (!keyDetails.isPresent()) {
            associationResult = fetchRepositoryAssociationResult(repositoryName, S3_TEST_NAME);
            repoAssociationArn = associationResult.getRepositoryAssociation().getAssociationArn();
        } else {
            associationResult = fetchRepositoryAssociationResult(repositoryName, S3_TEST_NAME_CMCMK);
            repoAssociationArnCMCMK = associationResult.getRepositoryAssociation().getAssociationArn();
        }

        return associationResult;
    }

    private void verifyGeneratedRecommendations(final boolean isSecurityJob, final boolean isCodeQualityJob,
                                                final List<RecommendationSummary> recommendations, final String codeReviewArn) {
        assertTrue(recommendations.size() > 0);
        if(isSecurityJob) {
            List<RecommendationSummary> securityRecommendations = recommendations.stream()
                    .filter(a -> a.getRecommendationId().contains(SECURITY_RECOMMENDATION_PREFIX))
                    .collect(Collectors.toList());
            assertTrue(securityRecommendations.size() > 1);
            log.info("Security Detectors successfully triggered for code review ARN {}", codeReviewArn);
        }

        if(isCodeQualityJob) {
            List<RecommendationSummary> codeQualityRecommendations = recommendations.stream()
                    .filter(a -> !a.getRecommendationId().contains(SECURITY_RECOMMENDATION_PREFIX))
                    .collect(Collectors.toList());
            assertTrue(codeQualityRecommendations.size() > 0);
            log.info("Code Quality successfully triggered for code review ARN {}", codeReviewArn);
        }
    }
}
