package com.amazon.awsgurufrontendservice.utils;

import com.amazon.awsgurufrontendservice.model.CommentWithCategory;
import com.amazon.coral.client.CallAttachmentVisitor;
import com.amazon.coral.client.Calls;
import com.amazon.coral.client.ClientBuilder;
import com.amazon.coral.retry.RetryStrategy;
import com.amazon.coral.retry.strategy.ExponentialBackoffAndJitterBuilder;
import com.amazon.coral.service.Identity;
import com.amazon.coralx.awsauth.AwsV4SigningCallVisitor;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSSessionCredentials;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.codestar.connections.CodeStar_connections_20191201Client;
import com.amazonaws.codestar.connections.Comment;
import com.amazonaws.codestar.connections.Commit;
import com.amazonaws.codestar.connections.ListBranchesInput;
import com.amazonaws.codestar.connections.ListBranchesParameters;
import com.amazonaws.codestar.connections.ListPullRequestCommentsInput;
import com.amazonaws.codestar.connections.ListPullRequestCommentsOutput;
import com.amazonaws.codestar.connections.ListPullRequestCommentsParameters;
import com.amazonaws.codestar.connections.ListPullRequestCommitsInput;
import com.amazonaws.codestar.connections.ListPullRequestCommitsOutput;
import com.amazonaws.codestar.connections.ListPullRequestCommitsParameters;
import com.amazonaws.codestar.connections.ListRepositoriesInput;
import com.amazonaws.codestar.connections.ListRepositoriesOutput;
import com.amazonaws.codestar.connections.ListRepositoriesParameters;
import com.amazonaws.codestar.connections.ProviderResourceNotFoundException;
import com.amazonaws.codestar.connections.ProviderThrottlingException;
import com.amazonaws.codestar.connections.ProviderUnavailableException;
import com.amazonaws.codestar.connections.CloudProviderUnavailableException;
import com.amazonaws.codestar.connections.RepositoryInfo;
import com.amazonaws.codestar.connections.impl.ListBranchesCall;
import com.amazonaws.codestar.connections.impl.ListPullRequestCommentsCall;
import com.amazonaws.codestar.connections.impl.ListPullRequestCommitsCall;
import com.amazonaws.codestar.connections.impl.ListRepositoriesCall;
import com.google.common.collect.Lists;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.WordUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.amazon.awsgurufrontendservice.utils.ConstantsHelper.EXPECTED_CATEGORIES;
import static com.amazon.awsgurufrontendservice.utils.ConstantsHelper.MAX_ATTEMPTS;
import static com.amazon.awsgurufrontendservice.utils.ConstantsHelper.MIN_PULL_REQUEST_DELAY_MILLIS;
import static com.amazon.awsgurufrontendservice.utils.ConstantsHelper.POLLING_FREQUENCY_MILLIS;
import static org.testng.Assert.assertTrue;
import static org.testng.AssertJUnit.assertFalse;

@Slf4j
public class CodeConnectHelper {

    private static final Duration LIST_BRANCHES_POLLING_FREQUENCY = Duration.ofSeconds(5); // 1 attempt should suffice
    private static final int LIST_BRANCHES_MAX_ATTEMPTS = 3;
    private static final int MAX_SIZE = 100;
    private static final String GAMMA = "Gamma";
    private static final String PREPROD = "Preprod";
    private static final String IAD_REGION = "us-east-1";

    protected final CodeStar_connections_20191201Client codeConnectClient;
    protected final AWSCredentialsProvider defaultCredentials = new DefaultAWSCredentialsProviderChain();

    public CodeConnectHelper(final String domain, final String region) {
        this.codeConnectClient = new ClientBuilder().remoteOf(CodeStar_connections_20191201Client.class)
                .withConfiguration(getConfigurationString(region, domain))
                .withCallVisitors(new CallAttachmentVisitor(Calls.retry(RETRY_STRATEGY)), new AwsV4SigningCallVisitor(defaultCredentials))
                .newClient();
    }

    private String getConfigurationString(final String region, final String envStage) {
        final String configQualifier;
        final String stage = WordUtils.capitalize(envStage);
        if (stage.contains(GAMMA) || stage.contains(PREPROD)) {
            configQualifier =  String.format("Base.%s.%s", IAD_REGION, GAMMA);
        } else {
            configQualifier = String.format("Base.%s.%s", region, stage);
        }
        log.info("Coral client config for Cassowary : {}", configQualifier);
        return configQualifier;
    }

    private static final RetryStrategy<Void> RETRY_STRATEGY = new ExponentialBackoffAndJitterBuilder()
            .retryOn(Calls.getCommonRecoverableThrowables())
            .retryOn(ProviderUnavailableException.class, ProviderThrottlingException.class, CloudProviderUnavailableException.class)
            .withInitialIntervalMillis(5000)
            .withExponentialFactor(2)
            .withMaxAttempts(3)
            .newStrategy();
 

    public void waitForRepositoryToBeAvailable(final String repositoryName, final String userName,
                                                final String connectionArn) throws InterruptedException {
        String fullRepositoryId = String.format("%s/%s", userName, repositoryName);
        int count = 0;
        do {
            Thread.sleep(LIST_BRANCHES_POLLING_FREQUENCY.toMillis());
            log.info("Checking if repository {} is available for {} times", fullRepositoryId, count+1);
        } while (!repositoryExists(fullRepositoryId, connectionArn) && ++count < LIST_BRANCHES_MAX_ATTEMPTS);

    }

    public boolean repositoryExists(final String fullRepositoryId, final String connectionArn) {
        ListBranchesParameters listBranchesParameters = ListBranchesParameters.builder()
                .withFullRepositoryId(fullRepositoryId)
                .build();
        ListBranchesInput listBranchesInput = ListBranchesInput.builder()
                .withConnectionArn(connectionArn)
                .withMaxResults(1)
                .withParameters(listBranchesParameters)
                .build();
        try {
            final ListBranchesCall listBranches = codeConnectClient.newListBranchesCall();
            listBranches.call(listBranchesInput);
            return true;
        } catch (ProviderResourceNotFoundException e) {
            log.info("Repository {} not yet available", fullRepositoryId);
            return false;
        }
    }

    // Cleanup old repos left over from previous executions.
    // Tests are supposed to cleanup after themselves, but sometimes these cleanups fail.
    // This is just an additional protection.
    public List<RepositoryInfo> getReposToDelete(final String owner, final String connectionArn) {
        log.info("Cleaning up old repos");
        final ListRepositoriesCall listRepositoriesCall = codeConnectClient.newListRepositoriesCall();
        List<RepositoryInfo> reposToDelete = new LinkedList<>();
        try {

            final ListRepositoriesParameters listRepositoriesParameters = ListRepositoriesParameters
                    .builder()
                    .withOwnerId(owner)
                    .build();

            final ListRepositoriesInput input = ListRepositoriesInput
                    .builder()
                    .withConnectionArn(connectionArn)
                    .withMaxResults(50)
                    .withParameters(listRepositoriesParameters)
                    .build();

            final ListRepositoriesOutput listRepoOutput = listRepositoriesCall.call(input);
            reposToDelete = listRepoOutput.getRepositories();
            return reposToDelete;
        } catch (Exception e) {
            // Swallowing all errors - the cleanup is not supposed to fail the tests.
            // Errors can be completely benign, e.g. several threads cleaning up the same repo.
            log.info("Error cleaning up old repos: " + e);
        }
        return reposToDelete;
    }


    public String getSourceFullHash(final String repositoryName, final String pullRequestId,
                                     final String owner, final String connectionArn,
                                     final String sourceCommitHash) {
        final ListPullRequestCommitsParameters pullRequestsParameters = ListPullRequestCommitsParameters.builder()
                .withFullRepositoryId(String.format("%s/%s", owner, repositoryName))
                .withPullRequestId(pullRequestId)
                .build();
        String nextToken = null;
        final List<Commit> commits = Lists.newArrayList();
        final ListPullRequestCommitsCall callForClient = codeConnectClient.newListPullRequestCommitsCall();
        do {
            final ListPullRequestCommitsInput input = ListPullRequestCommitsInput.builder()
                    .withConnectionArn(connectionArn)
                    .withParameters(pullRequestsParameters)
                    .withMaxResults(MAX_SIZE) // max number
                    .withNextToken(nextToken)
                    .build();

            final ListPullRequestCommitsOutput output = callForClient.call(input);
            nextToken = output.getNextToken();
            commits.addAll(output.getCommits());
        } while (nextToken != null);

        return commits.stream()
                .filter(commit -> commit.getCommitId()
                        .startsWith(sourceCommitHash))
                .collect(Collectors.toList())
                .get(0)
                .getCommitId(); //only 1 exists
    }


    public List<CommentWithCategory> waitForRecommendations(final String repositoryName, final String pullRequestId,
                                                             final String owner, final String connectionArn)
            throws InterruptedException {
        Thread.sleep(MIN_PULL_REQUEST_DELAY_MILLIS);
        final ListPullRequestCommentsCall callForClient = codeConnectClient.newListPullRequestCommentsCall();

        final String fullRepositoryName = String.format("%s/%s", owner, repositoryName);
        final ListPullRequestCommentsParameters commentsParameters = ListPullRequestCommentsParameters.builder()
                .withFullRepositoryId(fullRepositoryName)
                .withPullRequestId(pullRequestId)
                .build();
        final ListPullRequestCommentsInput request = ListPullRequestCommentsInput.builder()
                .withMaxResults(MAX_SIZE) // Max value supported
                .withConnectionArn(connectionArn)
                .withParameters(commentsParameters)
                .build();

        ListPullRequestCommentsOutput callResult = callForClient.call(request);
        List<Comment> data = callResult.getComments();

        int count = 0;
        while (data.isEmpty() && count++ < MAX_ATTEMPTS) {
            log.info(String.format("Waiting for recommendations from Guru.. Retry %d", count));
            Thread.sleep(POLLING_FREQUENCY_MILLIS);
            callResult = callForClient.call(request);
            data = callResult.getComments();
        }
        assertFalse(String.format(
                "No comments were published on the pull request for repo %s", fullRepositoryName), data.isEmpty());

        //sleep so that provider can return details about all the comments due to eventual consistency
        Thread.sleep(POLLING_FREQUENCY_MILLIS);

        return getCommentsWithCategories(request, callForClient);
    }

    private List<CommentWithCategory> getCommentsWithCategories(final ListPullRequestCommentsInput request,
                                                                final ListPullRequestCommentsCall callForClient) {
        final List<CommentWithCategory> result = new ArrayList<>();
        ListPullRequestCommentsOutput response;
        String nextToken = null;
        do {
            request.setNextToken(nextToken);
            response = callForClient.call(request);
            for (final Comment commentsForPullRequest : response.getComments()) {
                result.add(new CommentWithCategory(commentsForPullRequest.getCommentId(), commentsForPullRequest.getBody(), getCategory(
                        commentsForPullRequest.getFilePath())));
            }
            nextToken = response.getNextToken();
        } while (nextToken != null);
        return result;
    }

    // Files in directory tst-resource/<detector-category> are chosen to trigger <detector-category> and nothing else.
    // This way we can know the detection category from file path.
    // TODO: is there a better way? This can incorrectly succeed is some other detector starts triggering on these files.
    protected String getCategory(final String filePath) {
        final String[] parts = filePath.split("/");
        assertTrue(
                parts.length > 1, String.format("%s doesn't conform to expected file path of having minimum length 2",
                                                Arrays.toString(parts)));
        return parts[0];
    }
}
