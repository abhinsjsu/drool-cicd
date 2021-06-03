package com.amazon.awsgurufrontendservice;

import com.amazon.awsgurufrontendservice.github.GitHubCommitter;
import com.amazon.awsgurufrontendservice.model.CodeLanguage;
import com.amazon.awsgurufrontendservice.model.CommentWithCategory;
import com.amazon.awsgurufrontendservice.pullrequest.GitHubPullRequestRaiser;
import com.amazon.coral.client.Calls;
import com.amazon.coral.retry.RetryStrategy;
import com.amazon.coral.retry.RetryingCallable;
import com.amazon.coral.retry.strategy.ExponentialBackoffAndJitterBuilder;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.Protocol;
import com.amazonaws.Request;
import com.amazonaws.Response;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.codestar.connections.ProviderThrottlingException;
import com.amazonaws.codestar.connections.ProviderUnavailableException;
import com.amazonaws.guru.common.github.GitHubAdminApiFacade;
import com.amazonaws.guru.common.github.GitHubApiFacade;
import com.amazonaws.guru.common.github.domain.GitHubRef;
import com.amazonaws.guru.encryption.adapter.KmsAdapter;
import com.amazonaws.handlers.RequestHandler2;
import com.amazonaws.retry.PredefinedBackoffStrategies;
import com.amazonaws.retry.PredefinedRetryPolicies;
import com.amazonaws.retry.RetryPolicy;
import com.amazonaws.services.codestarconnections.AWSCodeStarconnections;
import com.amazonaws.services.codestarconnections.AWSCodeStarconnectionsClientBuilder;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.DeleteItemRequest;
import com.amazonaws.services.dynamodbv2.model.DeleteItemResult;
import com.amazonaws.services.dynamodbv2.model.GetItemRequest;
import com.amazonaws.services.dynamodbv2.model.GetItemResult;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.dynamodbv2.model.PutItemResult;
import com.amazonaws.services.gurufrontendservice.AWSGuruFrontendService;
import com.amazonaws.services.gurufrontendservice.AWSGuruFrontendServiceClient;
import com.amazonaws.services.gurufrontendservice.model.AWSGuruFrontendServiceException;
import com.amazonaws.services.gurufrontendservice.model.AssociateRepositoryRequest;
import com.amazonaws.services.gurufrontendservice.model.AssociateRepositoryResult;
import com.amazonaws.services.gurufrontendservice.model.CodeReview;
import com.amazonaws.services.gurufrontendservice.model.CodeReviewType;
import com.amazonaws.services.gurufrontendservice.model.CreateCodeReviewRequest;
import com.amazonaws.services.gurufrontendservice.model.CreateCodeReviewResult;
import com.amazonaws.services.gurufrontendservice.model.DescribeCodeReviewRequest;
import com.amazonaws.services.gurufrontendservice.model.DescribeCodeReviewResult;
import com.amazonaws.services.gurufrontendservice.model.DescribeRecommendationFeedbackRequest;
import com.amazonaws.services.gurufrontendservice.model.DescribeRepositoryAssociationRequest;
import com.amazonaws.services.gurufrontendservice.model.DescribeRepositoryAssociationResult;
import com.amazonaws.services.gurufrontendservice.model.DisassociateRepositoryRequest;
import com.amazonaws.services.gurufrontendservice.model.EncryptionOption;
import com.amazonaws.services.gurufrontendservice.model.JobState;
import com.amazonaws.services.gurufrontendservice.model.KMSKeyDetails;
import com.amazonaws.services.gurufrontendservice.model.ListCodeReviewsRequest;
import com.amazonaws.services.gurufrontendservice.model.ListCodeReviewsResult;
import com.amazonaws.services.gurufrontendservice.model.ListRecommendationFeedbackRequest;
import com.amazonaws.services.gurufrontendservice.model.ListRecommendationFeedbackResult;
import com.amazonaws.services.gurufrontendservice.model.ListRecommendationsRequest;
import com.amazonaws.services.gurufrontendservice.model.ListRepositoryAssociationsRequest;
import com.amazonaws.services.gurufrontendservice.model.ListRepositoryAssociationsResult;
import com.amazonaws.services.gurufrontendservice.model.RepositoryAssociation;
import com.amazonaws.services.kms.AWSKMSClientBuilder;
import com.amazonaws.services.kms.model.NotFoundException;
import com.amazonaws.services.gurufrontendservice.model.ProviderType;
import com.amazonaws.services.gurufrontendservice.model.PutRecommendationFeedbackRequest;
import com.amazonaws.services.gurufrontendservice.model.Reaction;
import com.amazonaws.services.gurufrontendservice.model.RecommendationFeedbackSummary;
import com.amazonaws.services.gurufrontendservice.model.RecommendationSummary;
import com.amazonaws.services.gurufrontendservice.model.Repository;
import com.amazonaws.services.gurufrontendservice.model.RepositoryAnalysis;
import com.amazonaws.services.gurufrontendservice.model.RepositoryAssociationState;
import com.amazonaws.services.gurufrontendservice.model.RepositoryAssociationSummary;
import com.amazonaws.services.gurufrontendservice.model.RepositoryHeadSourceCodeType;
import com.amazonaws.services.gurufrontendservice.model.Type;
import com.amazonaws.services.gurumetadataservice.AWSGuruMetadataService;
import com.amazonaws.services.gurumetadataservice.AWSGuruMetadataServiceClientBuilder;
import com.amazonaws.services.kms.model.DescribeKeyResult;
import com.google.gson.Gson;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.google.common.collect.ImmutableList;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang.WordUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.protocol.HttpContext;
import org.testng.Assert;

import javax.net.ssl.SSLException;
import java.io.File;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.ZoneId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.amazon.awsgurufrontendservice.utils.ConstantsHelper.DESTINATION_BRANCH_NAME;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

@Log4j2
public class IntegrationTestBase {
    protected static final Map<CodeLanguage, List<String>> EXPECTED_CATEGORIES_MAP =
        new HashMap<CodeLanguage, List<String>>() {{
            // TODO: enable javabestpractices, codeclone, input validation and info leak
            put(CodeLanguage.Java, ImmutableList.of("aws", "bugfix", "cloudformation", "concurrency", "resourceleak"));
            put(CodeLanguage.Python, ImmutableList.of("aws", "pythonbestpractices"));
        }};

    private static final Long BILLING_V1_REPO_SIZE = 275L;
    public static final List<List<String>> FILES_IN_EACH_COMMIT = Arrays.asList(
            Arrays.asList("ResourceLeak.java", "MissingPagination.java"),
            Arrays.asList("cloudformation.template.yml", "InfoLeak.java"),
            Arrays.asList("FormatIntWithPercentD.java", "AtomicityViolation.java"));

    // Cleaning up only a very few old repos at a time since we are afraid of getting throttled.
    // Normally there should be very few, if any, old repos to cleanup.
    // Reducing the size otherwise deletion will use all of the quota
    protected static final int MAX_REPOS_TO_CLEANUP = 20;
    // Should me much longer any workflow can take.
    protected static final long AGE_TO_CLEANUP_HOURS = 2;
    public static final String DELETE_INFERENCE_REPOSITORY_NAME_PREFIX = String.format("Inf-%s", System.currentTimeMillis());
    public static final String BITBUCKET_CREATE_CODEREVIEW_REPOSITORY_NAME_PREFIX = "bbCodeReview";
    public static final String CODECOMMIT_CREATE_CODEREVIEW_REPOSITORY_NAME_PREFIX = "CCCodeReview";
    public static final String GITHUB_CREATE_CODEREVIEW_REPOSITORY_NAME_PREFIX = "GHCodeReview";
    public static final String CODECOMMIT_RECOMMENDATIONFEEDBACK_REPOSITORY_NAME_PREFIX = "CCRecommendation";
    public static final String GITHUB_RECOMMENDATIONFEEDBACK_REPOSITORY_NAME_PREFIX = "GHRecommendation";
    public static final String BITBUCKET_RECOMMENDATIONFEEDBACK_REPOSITORY_NAME_PREFIX = "BBReco";
    public static final String GITHUB_REPOSITORY_ALREADY_CREATED = "name already exists on this account";
    public static final String GITHUB_BRANCH_NOT_FOUND = "Reference does not exist";
    protected static final long AGE_TO_CLEANUP_MILLISECONDS = TimeUnit.HOURS.toMillis(AGE_TO_CLEANUP_HOURS);
    protected static final String KEY_ID = "alias/cmcmkTestKey";

    private static final String FES_ENDPOINT_PATTERN_NON_PROD = "https://%s.%s.fe-service.guru.aws.a2z.com";
    private static final String FES_ENDPOINT_PATTERN_PROD = "https://codeguru-reviewer.%s.amazonaws.com";

    private static final int RANDOM_STRING_LENGTH_FOR_JOB_NAME = 10;
    private static final int MAX_MDS_RECOMMENDATION_JOB_NAME_SIZE = 300;
    public static final long MIN_CODE_COMMIT_CREATE_COMMIT_DELAY_MILLIS = TimeUnit.SECONDS.toMillis(5);
    public static final long MIN_CODE_COMMIT_REQUEST_DELAY_MILLIS = TimeUnit.SECONDS.toMillis(2);
    protected static final long MIN_PUT_RECOMMENDATION_FEEDBACK_DELAY_MILLIS = TimeUnit.SECONDS.toMillis(2);
    // No need to poll for the first X seconds - we know it won't finish faster than this.
    // Saves us some API calls.
    protected static final long MIN_PULL_REQUEST_DELAY_MILLIS = TimeUnit.MINUTES.toMillis(5);
    protected static final long MIN_CODEREVIEW_COMPLETED_DELAY_MILLIS = TimeUnit.MINUTES.toMillis(5);
    protected static final long MIN_ONBOARDING_DELAY_MILLIS = TimeUnit.MINUTES.toMillis(4);

    protected static final long POLLING_FREQUENCY_MILLIS = TimeUnit.SECONDS.toMillis(60);
    protected static final long LIST_ASSOCIATIONS_POLLING_FREQUENCY_MILLIS = 500;
    protected static final int MAX_ATTEMPTS = 20;
    // We will do describe for 20 * 30 = 600 seconds or 10 minutes on top of min delays
    // after which we can assume that operation is stuck.

    static final Integer S3_CLIENT_MAX_RETRIES = 5;
    static final Integer S3_CLIENT_RETRY_BASE_DELAY_MS = 5000;
    static final Integer S3_CLIENT_RETRY_MAX_BACKOFF_TIME_MS = 75000;

    protected final AWSCredentialsProvider DEFAULT_CREDENTIALS = new DefaultAWSCredentialsProviderChain();
    private static final String GAMMA = "Gamma";
    private static final String PREPROD = "Preprod";
    private static final String IAD_REGION = "us-east-1";

    private static final int CODE_REVIEW_NAME_MAX_CHARACTERS = 100;

    private static final Map<String, String> BODY_DUMMY_OBJECT = new HashMap<>();
    private Gson gson = new Gson();
    private static final String DYNAMO_DB_CACHE_KEY = "testName";
    private static final String DYNAMO_DB_CACHE_VALUE = "value";
    private static final String DYNAMO_DB_CACHE_TABLE_NAME = "TestCacheTable";

    static {
        String root;
        if (System.getenv().containsKey("HYDRA_FARGATE_RUN_WORKSPACE_DIR")) {
            root = System.getenv("HYDRA_FARGATE_RUN_WORKSPACE_DIR");
        } else {
            root = System.getenv("LAMBDA_TASK_ROOT");
        }
        System.setProperty("CORAL_CONFIG_PATH",
                String.format("%s%s%s", root, File.separator, "coral-config"));
    }

    public static final String testRepoPath = "tst-resource/billlingV2Repository";
    public static final String gitHubRepoPath = "tst-resource-with-lang";
    public static final HashMap<String, Long> BILLING_V2_BILLED_LOC = new HashMap<String, Long>() {{
        put(testRepoPath, 438L);
        put(gitHubRepoPath, 950L);
    }};

    private final ClassLoader classLoader = getClass().getClassLoader();

    private AWSGuruFrontendService guruClient;
    private final AWSGuruMetadataService mdsClient;
    private final AWSCodeStarconnections codeconnectClient;
    private final KmsAdapter kmsAdapter;

    private String domain;
    private String regionName;

    private AmazonDynamoDB amazonDynamoDB;


    public IntegrationTestBase(final String domain, final String regionName) {
        this.domain = domain;
        this.regionName = regionName;
        guruClient = getNewGuruClient();
        mdsClient = AWSGuruMetadataServiceClientBuilder
                .standard()
                .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(
                        String.format("https://%s.%s.md-service.guru.aws.a2z.com", regionName, domain),
                        regionName))
                .withClientConfiguration(
                        new ClientConfiguration().withProtocol(Protocol.HTTP).withConnectionTimeout(10000))
                .build();
        codeconnectClient = AWSCodeStarconnectionsClientBuilder.standard().withRegion(regionName).build();
        kmsAdapter = new KmsAdapter(AWSKMSClientBuilder.standard().withRegion(regionName).build());
        amazonDynamoDB = createAmazonDynamoDbClient(regionName);
    }

    @SuppressFBWarnings("BX_UNBOXING_IMMEDIATELY_REBOXED")
    public void verifyBilledLOCStoredInMDS(final String accountId, final String jobName, final String jobType, final String repoPath, final CodeReview codeReview)  throws Exception {

        Long actualBilledLOC = (long) codeReview.getMetrics().getMeteredLinesOfCodeCount();
        log.info("Get billedLOC stored in Metadata Service: {}", actualBilledLOC);

        // check whether billing V2 is launched
        List<Long> possibleRepoSizeList = Arrays.asList(BILLING_V1_REPO_SIZE, BILLING_V2_BILLED_LOC.get(repoPath));
        log.info("Checking repo path for each provider: {} ", repoPath);
        log.info("Expected billedLOC for target repo is :{}", BILLING_V2_BILLED_LOC.get(repoPath));

        boolean ifAcceptBothBilledLOC = acceptBothBilledLOC();

        log.info("If canaries test accept both billedLOC: {}", ifAcceptBothBilledLOC);
        if (ifAcceptBothBilledLOC) {
            assertTrue(possibleRepoSizeList.contains(actualBilledLOC), String.format("test repo billedLOC stored in MDS: %s for billingV2 isn't correct", actualBilledLOC));
        } else {
            assertEquals(BILLING_V2_BILLED_LOC.get(repoPath), actualBilledLOC);
        }
    }

    public boolean acceptBothBilledLOC() {
        //Check if gamma canaries account is enabled feature flag from 04/05 12:00pm PST
        final ZonedDateTime BILLING_V2_TEST_DATE = Instant.parse("2021-04-06T10:00:00Z").atZone(ZoneId.of("America/Los_Angeles"));
        ZonedDateTime currentPSTimeStamp = Instant.now().atZone(ZoneId.of("America/Los_Angeles"));
        boolean isFeatureFlagOnInGamma = currentPSTimeStamp.isAfter(BILLING_V2_TEST_DATE);

        //Check if billing v2 is launched in UTC date 04/06 5:00pm PST
        ZonedDateTime BILLING_V2_LAUNCH_DATE = Instant.parse("2021-04-07T02:00:00Z").atZone(ZoneId.of("UTC"));
        ZonedDateTime currentUTCimeStamp = Instant.now().atZone(ZoneId.of("UTC"));
        boolean isBillingV2Launched = currentUTCimeStamp.isAfter(BILLING_V2_LAUNCH_DATE);

        // only at prod stage and billingV2 is not launched, accept both old and v2 values
        if ("prod".equals(domain) && isBillingV2Launched) {
            return false;
        }
        // at gamma, prepod stage, before Mar 31 7:00AM PST, accept both old and v2 values
        if (!"prod".equals(domain) && isFeatureFlagOnInGamma) {
            return false;
        }

        return true;
    }

    public AWSGuruFrontendService getGuruClient() {
        return guruClient;
    }

    public AWSCodeStarconnections getCodeConnectClient() {
        return codeconnectClient;
    }

    public void refreshGuruClient() {
        this.guruClient = getNewGuruClient();
    }

    // Used to ensure new session every time.
    private AWSGuruFrontendService getNewGuruClient() {
        String endpoint;
        if ("prod".equals(domain)) {
            endpoint = String.format(FES_ENDPOINT_PATTERN_PROD, regionName);
        } else {
            endpoint = String.format(FES_ENDPOINT_PATTERN_NON_PROD, regionName, domain);
        }
        return AWSGuruFrontendServiceClient.builder()
                                           .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(endpoint, regionName))
                                           .withCredentials(getCredentials())
                                           // https://w.amazon.com/bin/view/Canaries/APICoverageOnboarding/
                                           .withClientConfiguration(new ClientConfiguration().withUserAgentSuffix("canary-generated"))
                                           .withRequestHandlers(getErrorRequestHandler())
                                           .build();
    }

    private RequestHandler2 getErrorRequestHandler() {
        return new RequestHandler2() {
            @Override
            public void afterError(Request<?> request, Response<?> response, Exception e) {
                super.afterError(request, response, e);
                if (e instanceof AWSGuruFrontendServiceException) {
                    AWSGuruFrontendServiceException ex = (AWSGuruFrontendServiceException) e;
                    if (StringUtils.equals(ex.getErrorCode(), "ForbiddenException")) {
                        log.error("WAF_DEBUG: Forbidden error occurred");
                        log.error("WAF_DEBUG: Request Parameters: " + request.getParameters());
                        log.error("WAF_DEBUG: Request Headers: " + request.getHeaders());
                        log.error("WAF_DEBUG: Request:" + request+ ex.getErrorCode());
                    }
                }
            }
        };
    }

    protected DescribeRepositoryAssociationResult waitForStateTransition(final RepositoryAssociationState state,
                                                                final String associationArn) throws
        InterruptedException {

        DescribeRepositoryAssociationRequest describeRepositoryAssociationRequest =
            new DescribeRepositoryAssociationRequest()
                    .withShowDeletedRepository(true)
                    .withAssociationArn(associationArn);

        int count = 0;
        DescribeRepositoryAssociationResult describeRepositoryAssociationResult;
        do {
            log.info(String.format("Waiting for state transition. Current state %s", state.toString()));
            Thread.sleep(POLLING_FREQUENCY_MILLIS);
            describeRepositoryAssociationResult =
                    guruClient.describeRepositoryAssociation(describeRepositoryAssociationRequest);
        } while (RepositoryAssociationState.fromValue(
            describeRepositoryAssociationResult.getRepositoryAssociation().getState()) == state
            && count++ < MAX_ATTEMPTS);
        return describeRepositoryAssociationResult;
    }

    public void disassociate(final String associationArn) throws InterruptedException {
        log.info(String.format("Disassociating repository %s", associationArn));
        getGuruClient().disassociateRepository(
                new DisassociateRepositoryRequest().withAssociationArn(associationArn));
        DescribeRepositoryAssociationResult result = waitForStateTransition(RepositoryAssociationState.Disassociating, associationArn);
        if (!result.getRepositoryAssociation().getProviderType().equals("GitHub")) {
            Assert.assertEquals(RepositoryAssociationState.Disassociated,
                RepositoryAssociationState.valueOf(result.getRepositoryAssociation().getState()),
                String.format("Association %s is still in Disassociating state.", associationArn));
        } else {
            //Todo github disassociation is failing and waiting for the fix.
        }
    }

    // Files in directory tst-resource/<detector-category> are chosen to trigger <detector-category> and nothing else.
    // This way we can know the detection category from file path.
    // TODO: is there a better way? This can incorrectly succeed is some other detector starts triggering on these files.
    protected String getCategory(final String filePath) {
        final String[] parts = filePath.split("/");
       assertTrue(parts.length > 1, String.format("%s doesn't conform to expected file path of having minimum length 2",
                                Arrays.toString(parts)));
       return parts[0];
    }

    /**
     * Returns appropriate credentials to be used when calling from non-prod stage vs prod stage.
     * The reason this method exist is because we can't create SLR in production before launch and,
     * therefore in order to run our canaries in prod, we've whitelisted a few accounts which will
     * by pass SLR creation check and will rely on the presence of GuruFullAccess being created.
     *
     * @return AWSCredentialsProvider as per the stage
     */
    protected AWSCredentialsProvider getCredentials() {
        return DEFAULT_CREDENTIALS;
    }

    // Need to use class loader when running on Lambda.
    protected String getRealPath(final String pathFromRepoRoot) {
        if (isRunningInLambda()) {
            return classLoader.getResource(pathFromRepoRoot).getFile();
        } else {
            return pathFromRepoRoot;
        }
    }

    protected void verifyComments(final List<CommentWithCategory> commentsWithCategories, final String repositoryName) {
        log.info(String.format("Got %s comments", commentsWithCategories.size()));
        final Set<String> triggeredCategories = getTriggeredCategories(commentsWithCategories);
        for (final String expectedCategory : EXPECTED_CATEGORIES_MAP.get(CodeLanguage.Java)) {
            assertTrue(triggeredCategories.contains(expectedCategory), String.format("%s did not trigger for repo %s. Triggered categories: %s.",
                                                                                            expectedCategory, repositoryName, triggeredCategories));
            log.info(String.format("Category %s successfully triggered for repo %s", expectedCategory, repositoryName));
        }
    }

    private Set<String> getTriggeredCategories(final List<CommentWithCategory> commentsWithCategories) {
        return commentsWithCategories.stream().map(CommentWithCategory::getCategory).collect(Collectors.toSet());
    }

    protected void verifyIsListed(final String repoName, final ProviderType providerType) {
        verifyIsListed(repoName, providerType, Optional.empty());
    }

    protected void verifyIsListed(final String repoName, final ProviderType providerType, final Optional<String> bucketName) {
        log.info(String.format("Verifying association for repository %s is listed.", repoName));
        final ListRepositoryAssociationsRequest request = new ListRepositoryAssociationsRequest().withNames(repoName);
        final ListRepositoryAssociationsResult response = getGuruClient().listRepositoryAssociations(request);
        assertEquals(1, response.getRepositoryAssociationSummaries().size());
        final RepositoryAssociationSummary association = response.getRepositoryAssociationSummaries().get(0);
        assertEquals(repoName, association.getName());
        assertEquals(providerType.toString(), association.getProviderType());
        assertEquals(RepositoryAssociationState.Associated.toString(), association.getState());
        if(bucketName.isPresent()) {
            final DescribeRepositoryAssociationRequest req = new DescribeRepositoryAssociationRequest().withAssociationArn(association.getAssociationArn());
            final DescribeRepositoryAssociationResult res = getGuruClient().describeRepositoryAssociation(req);
            final RepositoryAssociation repositoryAssociation = res.getRepositoryAssociation();
            assertEquals(bucketName.get(), repositoryAssociation.getS3RepositoryDetails().getBucketName());
            log.info(String.format("Association of %s bucket to repository %s is listed as expected.", bucketName.get(), repoName));
        }

        log.info(String.format("Association for repository %s is listed as expected.", repoName));
    }

    // Checking by availability of env variables.
    // https://docs.aws.amazon.com/lambda/latest/dg/lambda-environment-variables.html
    public static boolean isRunningInLambda() {
        return System.getenv("AWS_LAMBDA_FUNCTION_NAME") != null;
    }

    /**
     * get Set of categories from all recommendation summaries.
     * @param recommendationSummaries
     * @return
     */
    private Set<String> getTriggeredRecommendationCategories(
            final List<RecommendationSummary> recommendationSummaries) {
        return recommendationSummaries.stream().map(recommendationSummary ->
                getCategory(recommendationSummary.getFilePath())).collect(Collectors.toSet());
    }

    /**
     * This method helps to verify generated recommendations with expecting categories.
     * @param recommendationSummaries
     * @param repositoryName
     */
    @Deprecated
    protected void verifyRecommendations(
        final List<RecommendationSummary> recommendationSummaries,
        final String repositoryName) {
        verifyRecommendations(recommendationSummaries, repositoryName, EXPECTED_CATEGORIES_MAP.get(CodeLanguage.Java));
    }

    /**
     * This method helps to verify generated recommendations with expecting categories.
     * @param recommendationSummaries
     * @param repositoryName
     * @param expectedCategories
     */
    protected void verifyRecommendations(
            final List<RecommendationSummary> recommendationSummaries,
            final String repositoryName, final List<String> expectedCategories) {
        assertFalse(recommendationSummaries.isEmpty(),
                String.format("No recommendations found for repo %s", repositoryName));
        final Set<String> triggeredCategories = getTriggeredRecommendationCategories(recommendationSummaries);
        for (final String expectedCategory : expectedCategories) {
            assertTrue(triggeredCategories.contains(expectedCategory), String.format("%s did not trigger for repository %s. Triggered categories: %s",
                    expectedCategory, repositoryName, triggeredCategories));
            log.info(String.format("Category %s successfully triggered on %s", expectedCategory, repositoryName));
        }
    }

    protected String getCodeReviewName(
            @NonNull final ProviderType providerType,
            @NonNull final String repositoryName,
            @NonNull final String pullRequestNumber,
            @NonNull final String commitSHA) {
        final String jobName = String.join("-",
                providerType.toString().toUpperCase(),
                repositoryName,
                pullRequestNumber,
                commitSHA);

        return jobName.length() > MAX_MDS_RECOMMENDATION_JOB_NAME_SIZE ?
                jobName.substring(0, MAX_MDS_RECOMMENDATION_JOB_NAME_SIZE) : jobName;
    }

    public String getCodeReviewArn(
            @NonNull final String accountId,
            @NonNull final String codeReviewName,
            @NonNull final Type codeReviewType,
            @NonNull final String region) {
        return String.format("arn:aws:codeguru-reviewer:%s:%s:code-review:%s-%s", region, accountId, codeReviewType, codeReviewName);
    }

    /**
     * This method wait to get CodeReview and returns it.
     * @param accountId
     * @param codeReviewType
     * @param commitSHA
     * @param providerType
     * @param pullRequestNumber
     * @param region
     * @param repositoryName
     * @return
     * @throws Exception
     */
    protected CodeReview waitAndGetCodeReview(
            @NonNull final String accountId,
            @NonNull final Type codeReviewType,
            @NonNull final String commitSHA,
            @NonNull final ProviderType providerType,
            @NonNull final String pullRequestNumber,
            @NonNull final String region,
            @NonNull final String repositoryName) throws Exception {
        String codeReviewName = getCodeReviewName(providerType, repositoryName, pullRequestNumber, commitSHA);
        String codeReviewArn = getCodeReviewArn(accountId, codeReviewName, codeReviewType, region);
        return waitAndGetCodeReview(codeReviewArn);
    }

    /**
     * This method wait to get CodeReview and returns it.
     * @param codeReviewArn
     * @return
     * @throws Exception
     */
    public CodeReview waitAndGetCodeReview(String codeReviewArn) throws Exception {

        refreshGuruClient();
        log.info("Created code review explicitly {} ", codeReviewArn);
        Thread.sleep(MIN_CODEREVIEW_COMPLETED_DELAY_MILLIS);
        CodeReview codeReview =
                (CodeReview) new RetryingCallable(RETRY_STRATEGY, () -> {
                    DescribeCodeReviewResult describeCodeReviewResult = getGuruClient()
                            .describeCodeReview(new DescribeCodeReviewRequest()
                                    .withCodeReviewArn(codeReviewArn));
                    return describeCodeReviewResult.getCodeReview();
                }).call();
        assertFalse(codeReview == null,
                String.format("No code review for ARN: %s", codeReviewArn));
        log.info("Code review is created in FE service with arn: {}. Waiting for code review to finish...",
                codeReview.getCodeReviewArn());
        int count = 0;



        while (codeReview.getState().equals(JobState.Pending.toString()) && count++ < MAX_ATTEMPTS) {
            log.info("Checking code review state. Current state: {}, arn: {}. " +
                            "Attempt count: {}, will retry in {} ms...",
                    codeReview.getState(), codeReview.getCodeReviewArn(), count,
                    count, POLLING_FREQUENCY_MILLIS);
            Thread.sleep(POLLING_FREQUENCY_MILLIS);
            codeReview =
                    (CodeReview) new RetryingCallable(RETRY_STRATEGY, () -> {
                        DescribeCodeReviewResult describeCodeReviewResult = getGuruClient()
                                .describeCodeReview(new DescribeCodeReviewRequest()
                                        .withCodeReviewArn(codeReviewArn));
                        return describeCodeReviewResult.getCodeReview();
                    }).call();
        }
        log.info("Code review state {} for arn {}", codeReview.getState(), codeReview.getCodeReviewArn());
        assertFalse(!codeReview.getState().equals(JobState.Completed.toString()),
                String.format("Code review %s is in %s state", codeReview.getCodeReviewArn(), codeReview.getState()));
        return codeReview;
    }

    @Deprecated
    protected void codeReviewTest(final String repositoryName, final String codeReviewArn,
                                  final ProviderType providerType) throws  InterruptedException {
        codeReviewTest(repositoryName, codeReviewArn, providerType, CodeLanguage.Java);
    }

    protected void codeReviewTest(final String repositoryName, final String codeReviewArn,
                                  final ProviderType providerType, final CodeLanguage language)
        throws  InterruptedException {
        codeReviewTest(repositoryName, codeReviewArn, providerType, EXPECTED_CATEGORIES_MAP.get(language));
    }

    protected void codeReviewTestWithExpectedLanguage(final String repositoryName, final String codeReviewArn,
                                                      final ProviderType providerType,
                                                      final CodeLanguage expectedLanguage) throws InterruptedException {
        log.info("Starting Multi Lang codeReviewTest for target language {}", expectedLanguage.toString());
        codeReviewTest(repositoryName, codeReviewArn, providerType,
            ImmutableList.of(expectedLanguage.toLowerCaseString()));
    }

    protected void codeReviewTest(final String repositoryName, final String codeReviewArn,
                                  final ProviderType providerType, final List<String> expectedCategories)
        throws InterruptedException {
        refreshGuruClient();
        log.info("Starting codeReview{}Test for repository {} ", providerType, repositoryName);
        // DescribeCodeReview Api test. Validate CodeReviewSummary properties with describeCodeReviewResult.
        validateAndDescribeCodeReview(codeReviewArn, repositoryName, providerType);

        // ListRecommendations Api test. Verify expecting comments generated.
        final List<RecommendationSummary> recommendationSummaries =
                getListRecommendations(codeReviewArn);
        verifyRecommendations(recommendationSummaries, repositoryName, expectedCategories);

        // PutRecommendationFeedback api test with all generated recommendations.
        final Map<String, Reaction> reactionMap = new HashMap<String, Reaction>();
        // 3 is chosen arbitrarily as max items to check,
        // some cases with valid files may produce less recommendations hence apply min
        final int numItems = Math.min(recommendationSummaries.size(), 3);
        for (int i = 0; i < numItems; i++) {
            Thread.sleep(MIN_PUT_RECOMMENDATION_FEEDBACK_DELAY_MILLIS);
            final Reaction reaction = getRandomReaction();
            // Validates status code of putRecommendationFeedbackResult
            validateAndPutRecommendationFeedback(
                    codeReviewArn, recommendationSummaries.get(i).getRecommendationId(), reaction);
            reactionMap.put(recommendationSummaries.get(i).getRecommendationId(), reaction);
        }

        // ListRecommendationFeedbackRequest Api with MaxResult 1. It should return nextToken.
        final ListRecommendationFeedbackResult listRecommendationFeedbackResult = getGuruClient()
                                                                                          .listRecommendationFeedback(new ListRecommendationFeedbackRequest()
                                                                                                                              .withCodeReviewArn(codeReviewArn)
                                                                                                                              .withMaxResults(1));
        final String nextToken = listRecommendationFeedbackResult.getNextToken();
        Assert.assertEquals(1, listRecommendationFeedbackResult.getRecommendationFeedbackSummaries().size());
        Assert.assertNotNull(nextToken);
        final RecommendationFeedbackSummary recommendationFeedbackSummary = listRecommendationFeedbackResult
                                                                                    .getRecommendationFeedbackSummaries().get(0);

        // This checks recommendationFeedback reaction.
        Assert.assertEquals(1, recommendationFeedbackSummary.getReactions().size());

        // DescribeRecommendationFeedback Api test. It compares the reactions put earlier.
        log.info("Validating describe recommendation for repository: {} recommendationId: {} reaction: {}",
                 repositoryName, recommendationFeedbackSummary.getRecommendationId(),
                 recommendationFeedbackSummary.getReactions().get(0));
        validateAndDescribeRecommendationFeedback(codeReviewArn,
                                                  recommendationFeedbackSummary.getRecommendationId(),
                                                  reactionMap.get(recommendationFeedbackSummary.getRecommendationId()));

        // ListRecommendationFeedbackRequest Api with previous nextToken. It should not return nextToken.
        final List<RecommendationFeedbackSummary> recommendationFeedbackSummaries = getGuruClient()
                                                                                            .listRecommendationFeedback(
                                                                                                    new ListRecommendationFeedbackRequest()
                                                                                                            .withCodeReviewArn(codeReviewArn)
                                                                                                            .withNextToken(nextToken))
                                                                                            .getRecommendationFeedbackSummaries();
        assertTrue(!recommendationFeedbackSummaries.isEmpty(), "No recommendation feedback found");
    }

    protected void codeReviewListPaginateTest(final String repositoryName, final ProviderType providerType) {
        //TODO : generate more recommendations to validate 1+ pages.
        refreshGuruClient();
        log.info("Starting codeReviewListPaginate{}Test for repository {} ", providerType, repositoryName);
        // ListRecommendationFeedbackRequest Api with MaxResult 1. It should return nextToken.
        final ListCodeReviewsResult listCodeReviewsResult1 = getGuruClient()
                                                                     .listCodeReviews(new ListCodeReviewsRequest()
                                                                                              .withType(Type.PullRequest.toString())
                                                                                              .withMaxResults(1));

        final String nextToken = listCodeReviewsResult1.getNextToken();
        Assert.assertEquals(1, listCodeReviewsResult1.getCodeReviewSummaries().size());
        Assert.assertNotNull(nextToken);

        final ListCodeReviewsResult listCodeReviewsResult2 = getGuruClient()
                                                                     .listCodeReviews(new ListCodeReviewsRequest()
                                                                                              .withType(Type.PullRequest.toString())
                                                                                              .withNextToken(nextToken));
        assertTrue(!listCodeReviewsResult2.getCodeReviewSummaries().isEmpty(),
                          "No code reviews found for codeReviewListPaginateGithubTest");
    }

    protected List<RecommendationSummary> waitForCodeGuruReviewerRecommendations(
            @NonNull final String repositoryName,
            @NonNull final String codeReviewArn) throws Exception {
        return waitForCodeGuruReviewerRecommendations(repositoryName, codeReviewArn, true);
    }

    /**
     * This method waits till recommendations are generated.
     * @param repositoryName
     * @param codeReviewArn
     * @return
     * @throws Exception
     */
    protected List<RecommendationSummary> waitForCodeGuruReviewerRecommendations(
            @NonNull final String repositoryName,
            @NonNull final String codeReviewArn,
            @NonNull final boolean shouldVerifyRecommendations) throws Exception {

        refreshGuruClient();
        final ListRecommendationsRequest listRecommendationsRequest = new ListRecommendationsRequest()
                .withCodeReviewArn(codeReviewArn);

        List<RecommendationSummary> recommendationSummaries =
                (List<RecommendationSummary>) new RetryingCallable(RETRY_STRATEGY, () ->
                        getGuruClient()
                                .listRecommendations(listRecommendationsRequest)
                                .getRecommendationSummaries()).call();
        int count = 0;
        while (recommendationSummaries.isEmpty() && count++ < MAX_ATTEMPTS) {
            log.info("Waiting for recommendations from Guru for repository {}.. Retry {}", repositoryName, count);
            Thread.sleep(POLLING_FREQUENCY_MILLIS);
            recommendationSummaries =
                    (List<RecommendationSummary>) new RetryingCallable(RETRY_STRATEGY, () ->
                            getGuruClient()
                                    .listRecommendations(listRecommendationsRequest)
                                    .getRecommendationSummaries()).call();
        }
        if(shouldVerifyRecommendations) {
            verifyRecommendations(recommendationSummaries, repositoryName);
        }
        return recommendationSummaries;
    }

    public String validateAndCreateCodeReview(
            @NonNull final String associationArn,
            @NonNull final String codeReviewName,
            @NonNull final String sourceBranchName) {
        CreateCodeReviewResult createCodeReviewResult = getGuruClient().createCodeReview(
                new CreateCodeReviewRequest()
                        .withName(codeReviewName)
                        .withRepositoryAssociationArn(associationArn)
                        .withType(new CodeReviewType()
                                .withRepositoryAnalysis(new RepositoryAnalysis()
                                        .withRepositoryHead(new RepositoryHeadSourceCodeType()
                                                .withBranchName(sourceBranchName)
                                        ))));
        Assert.assertEquals(createCodeReviewResult.getCodeReview().getName(), codeReviewName);

        return createCodeReviewResult.getCodeReview().getCodeReviewArn();
    }

    /**
     * This method calls DescribeCodeReview api and validates it.
     * @param codeReviewArn
     * @param repositoryName
     * @param providerType
     */
    protected void validateAndDescribeCodeReview(
            @NonNull final String codeReviewArn,
            @NonNull final String repositoryName,
            @NonNull final ProviderType providerType
            ) {
        log.info("Validating DescribeCodeReview for codeReviewArn {} ", codeReviewArn);
        final DescribeCodeReviewResult describeCodeReviewResult = getGuruClient().describeCodeReview(
                new DescribeCodeReviewRequest()
                        .withCodeReviewArn(codeReviewArn));
        Assert.assertEquals(codeReviewArn, describeCodeReviewResult.getCodeReview().getCodeReviewArn());
        Assert.assertEquals(repositoryName, describeCodeReviewResult.getCodeReview().getRepositoryName());
        Assert.assertEquals(providerType.toString(),
                describeCodeReviewResult.getCodeReview().getProviderType());
        Assert.assertEquals(JobState.Completed.toString(),
                describeCodeReviewResult.getCodeReview().getState());
    }

    /**
     * This method calls ListRecommendations api and validates each recommendations.
     * @param codeReviewArn
     * @return
     */
    protected List<RecommendationSummary> getListRecommendations(
            @NonNull final String codeReviewArn) {
        log.info("Validating ListRecommendations for codeReviewArn {} ", codeReviewArn);
        final ListRecommendationsRequest listRecommendationsRequest = new ListRecommendationsRequest()
                .withCodeReviewArn(codeReviewArn);
        final List<RecommendationSummary> recommendationSummaries = getGuruClient()
                .listRecommendations(listRecommendationsRequest).getRecommendationSummaries();
        return recommendationSummaries;
    }

    /**
     * This method calls putRecommendationFeedback api and validates http status code of response.
     * @param codeReviewArn
     * @param recommendationId
     * @param reaction
     */
    protected  void validateAndPutRecommendationFeedback(
            @NonNull final String codeReviewArn,
            @NonNull final String recommendationId,
            @NonNull final Reaction reaction) {
        log.info("Validating PutRecommendationFeedback for repository {} ", codeReviewArn);
        Assert.assertEquals(200, getGuruClient().putRecommendationFeedback(
                new PutRecommendationFeedbackRequest()
                        .withCodeReviewArn(codeReviewArn)
                        .withRecommendationId(recommendationId)
                        .withReactions(reaction)).getSdkHttpMetadata().getHttpStatusCode());
    }

    /**
     * This method calls DescribeRecommendationFeedback api and validates the response.
     * @param codeReviewArn
     * @param recommendationId
     * @param reaction
     */
    protected void  validateAndDescribeRecommendationFeedback(
            @NonNull final String codeReviewArn,
            @NonNull final String recommendationId,
            @NonNull final Reaction reaction) {

        log.info("Validating DescribeRecommendationFeedback for codeReviewArn {} ", codeReviewArn);
        final List<String> describeRecommendationFeedbackReactions = getGuruClient()
                .describeRecommendationFeedback(
                        new DescribeRecommendationFeedbackRequest()
                                .withCodeReviewArn(codeReviewArn)
                                .withRecommendationId(recommendationId)
                ).getRecommendationFeedback().getReactions();
        assertTrue(!describeRecommendationFeedbackReactions.isEmpty());
        Assert.assertEquals(reaction.toString(), describeRecommendationFeedbackReactions.get(0));
    }


    /**
     * This method return random reaction value.
     * @return
     */
    public static Reaction getRandomReaction() {
        final Random random = new Random();
        return Reaction.values()[random.nextInt(Reaction.values().length)];
    }

    protected static final RetryStrategy<Void> RETRY_STRATEGY = new ExponentialBackoffAndJitterBuilder()
            .retryOn(Calls.getCommonRecoverableThrowables())
            .retryOn(ProviderUnavailableException.class, ProviderThrottlingException.class)
            .withInitialIntervalMillis(5000)
            .withExponentialFactor(2)
            .withMaxAttempts(3)
            .newStrategy();

    protected String getConfigurationString(final String region, final String envStage) {
        final String configQualifier;
        final String stage = WordUtils.capitalize(envStage);
        if (GAMMA.equals(stage) || PREPROD.equals(stage)) {
            configQualifier =  String.format("Base.%s.%s", IAD_REGION, GAMMA);
        } else {
            configQualifier = String.format("Base.%s.%s", region, stage);
        }
        log.info("Coral client config for Cassowary : {}", configQualifier);
        return configQualifier;
    }

    protected CloseableHttpClient buildHttpClient() {
        final Duration connectTimeout = Duration.ofSeconds(5);
        final Duration socketTimeout = Duration.ofSeconds(20);
        return HttpClientBuilder.create()
                .setMaxConnTotal(50)
                .setMaxConnPerRoute(50)
                .setKeepAliveStrategy(new ConnectionKeepAliveStrategy()
                {
                    @Override
                    public long getKeepAliveDuration(HttpResponse response, HttpContext context)
                    {
                        return 1L;
                    }
                })
                .setDefaultRequestConfig(
                        RequestConfig.custom()
                                // Time between receiving bytes
                                .setSocketTimeout((int) socketTimeout.toMillis())
                                // Time to open TCP connection
                                .setConnectTimeout((int) connectTimeout.toMillis())
                                .build())
                .setRetryHandler(new DefaultHttpRequestRetryHandler() {
                    @Override
                    public boolean retryRequest(IOException exception, int executionCount, HttpContext context) {
                        if (executionCount <= getRetryCount() &&
                                (exception instanceof SocketTimeoutException
                                        || exception instanceof ConnectTimeoutException
                                        || exception instanceof SSLException)) {
                            log.info(String.format("Retrying socket or connection timeout, " +
                                    "execution count %d", executionCount));
                            return true;
                        }
                        return super.retryRequest(exception, executionCount, context);
                    }
                })
                .build();
    }

    protected  AssociateRepositoryResult onboardBase(
            final String repositoryName,
            final Repository repository) throws InterruptedException {
        return onboardBase(repositoryName, repository, null);
    }

    protected AssociateRepositoryResult onboardBase(final String repositoryName,
                                                    final Repository repository,
                                                    final Map<String, String> tags)
            throws InterruptedException {
        return onboardBase(repositoryName, repository, Optional.empty(), tags);
    }



    protected AssociateRepositoryResult onboardBase(final String repositoryName,
                                                    final Repository repository,
                                       final Optional<KMSKeyDetails> kmsKeyDetails,
                                       final Map<String, String> tags) throws InterruptedException {
        if (repository.getS3Bucket() != null && StringUtils.isNotBlank(repository.getS3Bucket().getBucketName())) {
            log.info("Associating S3 repository {} to the given bucket: {}",
                     repositoryName, repository.getS3Bucket().getBucketName());
        }
        AssociateRepositoryRequest associationsRequest = new AssociateRepositoryRequest()
            .withRepository(repository)
            .withTags(tags);
        log.info("Associating repository {} with KMS key details? {}", repositoryName, kmsKeyDetails.isPresent());
        if (kmsKeyDetails.isPresent()) {
            log.info( "Encryption Mode = {}, KMSKeyId = {}", kmsKeyDetails.get().getEncryptionOption(),
                kmsKeyDetails.get().getKMSKeyId());
        }
        kmsKeyDetails.ifPresent(kmsDetails -> associationsRequest.setKMSKeyDetails(kmsDetails));
        log.info("Associating repository {}", repositoryName);
        AssociateRepositoryResult associateRepositoryResult =
            getGuruClient()
                .associateRepository(associationsRequest);
        String associationArn = associateRepositoryResult.getRepositoryAssociation().getAssociationArn();

        DescribeRepositoryAssociationResult describeRepositoryAssociationResult = waitForStateTransition(
            RepositoryAssociationState.Associating,
            associationArn
        );
        Assert.assertEquals(RepositoryAssociationState.fromValue(describeRepositoryAssociationResult
            .getRepositoryAssociation().getState()), RepositoryAssociationState.Associated,
            String.format("Did not reach the desired state for repo %s", repositoryName));
        log.info("Associated repository successfully repositoryName={}, associationArn={}",
            repositoryName, associationArn);

        KMSKeyDetails kmsKeyDetailsExpected = kmsKeyDetails.orElseGet(this::createKMSKeyDetailsAoCmk);
        validateKmsDetails(kmsKeyDetailsExpected, describeRepositoryAssociationResult.getRepositoryAssociation().getKMSKeyDetails());
        Assert.assertEquals(repositoryName, describeRepositoryAssociationResult.getRepositoryAssociation().getName());

        return associateRepositoryResult;
    }

    protected KMSKeyDetails createKMSKeyDetailsAoCmk(){
        KMSKeyDetails kmsKeyDetails = new KMSKeyDetails();
        kmsKeyDetails.setEncryptionOption(EncryptionOption.AWS_OWNED_CMK);
        return kmsKeyDetails;
    }

    protected KMSKeyDetails createKMSKeyDetailsCmCmk() {
        DescribeKeyResult result;
        try {
            log.info("Check if KEY ={} is exist", KEY_ID);
            result = kmsAdapter.describeKey(KEY_ID);
        } catch (NotFoundException ex) {
            log.warn("KMS Key with ID={} not found, Falling back to creating new one", KEY_ID);
            log.info(" Start create key with alias: {}", KEY_ID);
            kmsAdapter.createKey(KEY_ID);
            result  = kmsAdapter.describeKey(KEY_ID);
            log.info("Key created successfully.");
        }
        KMSKeyDetails kmsKeyDetails = new KMSKeyDetails();
        kmsKeyDetails.setEncryptionOption(EncryptionOption.CUSTOMER_MANAGED_CMK);
        kmsKeyDetails.setKMSKeyId(result.getKeyMetadata().getKeyId());
        return kmsKeyDetails;
    }

    protected void validateKmsDetails(final KMSKeyDetails kmsKeyDetailsExpected, final KMSKeyDetails kmsKeyDetailsActual) {
        Assert.assertEquals(kmsKeyDetailsExpected.getEncryptionOption(), kmsKeyDetailsActual.getEncryptionOption());
        if (kmsKeyDetailsExpected.getEncryptionOption().equals(EncryptionOption.CUSTOMER_MANAGED_CMK.toString())) {
            Assert.assertEquals(kmsKeyDetailsExpected.getKMSKeyId(), kmsKeyDetailsActual.getKMSKeyId());
        }
    }


    /**
     * This method helps to create repository and associate it in Github.
     * It also creates pull requests.
     * @param repositoryName
     * @return
     * @throws Exception
     */
    protected GitHubPullRequestRaiser setupGitHubPullRequestRaiser(
            @NonNull final String recommendationSourceBranchName,
            @NonNull final String repositoryName,
            final String owner,
            final GitHubApiFacade gitHubApiFacade,
            final GitHubAdminApiFacade gitHubAdminApiFacade) throws Exception {

        TestGitHubRepository destinationRepository = TestGitHubRepository.builder()
                .name(repositoryName)
                .owner(owner)
                .branchName(DESTINATION_BRANCH_NAME)
                .gitHubAdminApiFacade(gitHubAdminApiFacade)
                .build();

        TestGitHubRepository sourceRepository = TestGitHubRepository.builder()
                .name(repositoryName)
                .owner(owner)
                .branchName(recommendationSourceBranchName)
                .gitHubAdminApiFacade(gitHubAdminApiFacade)
                .build();

        setupSourceBranchForPullRequest(DESTINATION_BRANCH_NAME, sourceRepository);
        return new GitHubPullRequestRaiser(
                destinationRepository,
                sourceRepository,
                gitHubApiFacade);
    }

    protected void setupSourceBranchForPullRequest(
        final String destinationBranchName,
        final TestGitHubRepository sourceRepository) throws Exception {
        final String repositoryPath = "tst-resource/repository";
        setupSourceBranchForPullRequest(destinationBranchName, sourceRepository, repositoryPath);
    }

    protected void setupSourceBranchForPullRequest(
            final String destinationBranchName,
            final TestGitHubRepository sourceRepository,
            final String repositoryPath) throws Exception {
        GitHubCommitter sourceCommitter = new GitHubCommitter(sourceRepository);
        // Get the ref of master head (GetRef)
        final GitHubRef sourceFeatureRef = sourceCommitter.createBranch(sourceRepository.getBranchName(),
                destinationBranchName);

        sourceCommitter.commit(sourceFeatureRef, repositoryPath);
    }


    public AmazonS3 createAmazonS3Client(final String region) {
        return AmazonS3ClientBuilder.standard()
                .withRegion(region)
                .withCredentials(getCredentials())
                .withClientConfiguration(new ClientConfiguration()
                        .withRetryPolicy(new RetryPolicy(
                                PredefinedRetryPolicies.DEFAULT_RETRY_CONDITION,
                                new PredefinedBackoffStrategies.ExponentialBackoffStrategy(
                                        S3_CLIENT_RETRY_BASE_DELAY_MS,
                                        S3_CLIENT_RETRY_MAX_BACKOFF_TIME_MS),
                                S3_CLIENT_MAX_RETRIES, false)))
                .build();
    }

    public AmazonDynamoDB createAmazonDynamoDbClient(final String region) {
        return AmazonDynamoDBClientBuilder.standard()
                .withRegion(region)
                .withCredentials(getCredentials())
                .withClientConfiguration(new ClientConfiguration()
                        .withRetryPolicy(new RetryPolicy(
                                PredefinedRetryPolicies.DEFAULT_RETRY_CONDITION,
                                new PredefinedBackoffStrategies.ExponentialBackoffStrategy(
                                        S3_CLIENT_RETRY_BASE_DELAY_MS, // Use the same Retry policy as S3 client
                                        S3_CLIENT_RETRY_MAX_BACKOFF_TIME_MS),
                                S3_CLIENT_MAX_RETRIES, false)))
                .build();
    }

    public void putItemInDynamo(final String value, final String testName) {
        try {
            Map<String, AttributeValue> itemToAdd = new HashMap<String, AttributeValue>();
            itemToAdd.put(DYNAMO_DB_CACHE_KEY, new AttributeValue(testName));
            itemToAdd.put(DYNAMO_DB_CACHE_VALUE, new AttributeValue(value));

            PutItemResult putItemOutcome = amazonDynamoDB.putItem(new PutItemRequest()
                    .withTableName(DYNAMO_DB_CACHE_TABLE_NAME)
                    .withItem(itemToAdd)
            );
            log.info("PutItem outcome {}", putItemOutcome);
        } catch (final AmazonServiceException e) {
            log.info("Failed to store in Dynamo DB table. Entry {}", value);
        }
    }

    public Map<String, String> getItemFromDynamo(final String testName) {
        try {
            Map<String, AttributeValue> itemKey = new HashMap<String, AttributeValue>();
            itemKey.put(DYNAMO_DB_CACHE_KEY, new AttributeValue(testName));
            GetItemRequest getItemRequest = new GetItemRequest()
                    .withTableName(DYNAMO_DB_CACHE_TABLE_NAME)
                    .withKey(itemKey);
            GetItemResult getItemResult = amazonDynamoDB.getItem(getItemRequest);
            Map<String, AttributeValue> dynamoItem = getItemResult.getItem();
            log.info("Get Item Result: {}", dynamoItem);
            if(dynamoItem != null && dynamoItem.containsKey(DYNAMO_DB_CACHE_VALUE)) {
                return gson.fromJson(dynamoItem.get(DYNAMO_DB_CACHE_VALUE).getS(),
                        BODY_DUMMY_OBJECT.getClass());
            }
        } catch (final AmazonServiceException e) {
            log.info("Failed to get entry from dynamo db");
        }
        return new HashMap<>();
    }

    /**
     * Invalidate an entry from the Dynamo DB cache that stores association IDs. This is required
     * in the event that an association ID changes after re-associating a repository.
     * @param dynamoDbCacheKey key for the Dynamo DB association ID entry
     */
    protected void invalidateDynamoDBCache(final String dynamoDbCacheKey) {
        try {
            log.info("Invalidating Dynamo DB cache key {} in Dynamo DB table {}", dynamoDbCacheKey, DYNAMO_DB_CACHE_TABLE_NAME);
            Map<String, AttributeValue> itemToDelete = new HashMap<String, AttributeValue>();
            itemToDelete.put(DYNAMO_DB_CACHE_KEY, new AttributeValue(dynamoDbCacheKey));
            DeleteItemResult deleteItemOutcome = amazonDynamoDB.deleteItem(new DeleteItemRequest()
                .withTableName(DYNAMO_DB_CACHE_TABLE_NAME)
                .withKey(itemToDelete)
            );
            log.info("DeleteItem outcome {}", deleteItemOutcome);
        } catch (final AmazonServiceException e) {
            log.info("Failed to delete {} from Dynamo DB table {}", dynamoDbCacheKey, DYNAMO_DB_CACHE_TABLE_NAME);
        }
    }

    private String getDynamoDbCacheValue(final String repoAssociationArn, final String repositoryName) {
        Map<String, String> valueMap = new HashMap<>();
        valueMap.put(String.format("%s:associationArn", repositoryName), repoAssociationArn);
        return gson.toJson(valueMap);
    }

    private DescribeRepositoryAssociationResult describeRepositoryAssociation(final String repoAssociationArn) {

        DescribeRepositoryAssociationRequest describeRepositoryAssociationRequest =
                new DescribeRepositoryAssociationRequest()
                        .withAssociationArn(repoAssociationArn);
        return getGuruClient().describeRepositoryAssociation(describeRepositoryAssociationRequest);
    }

    @SneakyThrows
    public String listRepositoryAssociationAndStoreInDynamo(final String repositoryName, final String itemName) {
        ListRepositoryAssociationsRequest request = new ListRepositoryAssociationsRequest()
                .withNames(repositoryName);
        ListRepositoryAssociationsResult listRepositoryAssociationsResult = getGuruClient()
                .listRepositoryAssociations(request);
        String nextToken = listRepositoryAssociationsResult.getNextToken();
        List<RepositoryAssociationSummary> repositoryAssociationSummaries = new ArrayList<>();
        repositoryAssociationSummaries.addAll(listRepositoryAssociationsResult.getRepositoryAssociationSummaries());
        int count = 0;
        log.info("Retrieving Association for Repository {} to update Dynamo DB cache key {} in Table {}", repositoryName, itemName, DYNAMO_DB_CACHE_TABLE_NAME);
        while(nextToken!=null && repositoryAssociationSummaries.isEmpty()) {
            request = new ListRepositoryAssociationsRequest()
                    .withNames(repositoryName)
                    .withNextToken(nextToken);
            ListRepositoryAssociationsResult associationsResult = getGuruClient().listRepositoryAssociations(request);
            nextToken = associationsResult.getNextToken();
            repositoryAssociationSummaries.addAll(associationsResult.getRepositoryAssociationSummaries());
            Thread.sleep(LIST_ASSOCIATIONS_POLLING_FREQUENCY_MILLIS);
            log.info("Number of times calling FE ListRepositoryAssociations API for Repository {}: {}", repositoryName, count++);
        }
        log.info("FE ListRepositoryAssociations API response for Repository {}: {}", repositoryName, repositoryAssociationSummaries);
        final String repositoryAssociationArn = repositoryAssociationSummaries.get(0).getAssociationArn();

        // Put in Dynamo DB Table
        log.info("Updating Dynamo DB cache key {} in Table {} for Repository {} with Association {}", itemName, DYNAMO_DB_CACHE_TABLE_NAME, repositoryName, repositoryAssociationArn);
        putItemInDynamo(getDynamoDbCacheValue(repositoryAssociationArn, repositoryName), itemName);

        return repositoryAssociationArn;
    }

    public DescribeRepositoryAssociationResult fetchRepositoryAssociationResult(final String repositoryName, final String itemName) {
        log.info("Checking dynamo DB test cache for association");
        String repoAssociationArn;
        Map<String, String> cachedValues = getItemFromDynamo(itemName);
        if(cachedValues.containsKey(String.format("%s:associationArn", repositoryName))) {
            repoAssociationArn = cachedValues.get(String.format("%s:associationArn", repositoryName));
        } else {
            repoAssociationArn = listRepositoryAssociationAndStoreInDynamo(repositoryName, itemName);
        }

        DescribeRepositoryAssociationResult associationResult = describeRepositoryAssociation(repoAssociationArn);
        log.info("Associated repository successfully {} with association {}",
                repositoryName, associationResult.getRepositoryAssociation().getAssociationId());
        return associationResult;
    }

    public String checkAndTrimCodeReviewName(final String codeReviewName) {
        if (codeReviewName.length() > CODE_REVIEW_NAME_MAX_CHARACTERS) {
            log.info("Code Review {} name is longer than {}, it will be trimmed. ", codeReviewName,
                CODE_REVIEW_NAME_MAX_CHARACTERS);
        }
        return codeReviewName.substring(0, Math.min(codeReviewName.length(), CODE_REVIEW_NAME_MAX_CHARACTERS));
    }

    protected static String getRepositoryName(final String domain, final String region, final String providerType) {
        return String.format("%s-%s-%s-%s", providerType, domain, region, RandomStringUtils.randomAlphanumeric(16));
    }
}
