package com.amazon.awsgurufrontendservice;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.amazon.coral.retry.RetryStrategy;
import com.amazon.coral.retry.RetryingCallable;
import com.amazon.awsgurufrontendservice.codecommit.CodeCommitCommiter;
import com.amazon.awsgurufrontendservice.model.CommentWithCategory;
import com.amazon.coral.retry.strategy.ExponentialBackoffAndJitterBuilder;
import com.amazonaws.services.codecommit.model.AWSCodeCommitException;
import com.amazonaws.services.codecommit.model.Comment;
import com.amazonaws.services.codecommit.model.CommentsForPullRequest;
import com.amazonaws.services.codecommit.model.CreateCommitResult;
import com.amazonaws.services.codecommit.model.GetCommentsForPullRequestRequest;
import com.amazonaws.services.codecommit.model.GetCommentsForPullRequestResult;
import com.amazonaws.services.codecommit.model.PullRequest;
import com.amazonaws.services.gurufrontendservice.model.ConflictException;
import com.amazonaws.services.gurufrontendservice.model.KMSKeyDetails;
import lombok.extern.log4j.Log4j2;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import static org.testng.Assert.assertFalse;

@Log4j2
@Test(groups = {"codecommit-pullrequest-inference", "inference"})
public class CodeCommitPullRequestInferenceTestSuite extends CodeCommitTestSuite {

    private static final int WAIT_TIME = 2000;
    private static final float BACK_OFF_FACTOR = 0.5f;
    private static final int CODECOMMIT_API_RETRIES = 3;
    protected static final int MAX_ATTEMPTS = 20;

    protected static final RetryStrategy<Void> RETRY_STRATEGY = new ExponentialBackoffAndJitterBuilder()
            .retryOn(AWSCodeCommitException.class)
            .withMaxElapsedTimeMillis(WAIT_TIME)
            .withMaxAttempts(CODECOMMIT_API_RETRIES)
            .withExponentialFactor(BACK_OFF_FACTOR)
            .newStrategy();

    @Parameters({"domain", "region"})
    public CodeCommitPullRequestInferenceTestSuite(final String domain,
                                                   final String region) {
        super(domain, region);
    }

    @Parameters({"domain", "region"})
    @Test(groups = {"canary", "codecommit-pullrequest-inference-canary"})
    public void pullRequest_WithCodeCommit_SingleSourceCommit(
            final String domain,
            final String region) throws Exception {
        refreshGuruClient();
        final String CodeCommitPullRequestSourceBranchName = String.format("SingleSourceCommitFeature-%s", System.currentTimeMillis());
        String repositoryName =
            String.format("%s-CCSingleSource-%s-%s", DELETE_INFERENCE_REPOSITORY_NAME_PREFIX, domain, region);
        codeCommitPullRequest(CodeCommitPullRequestSourceBranchName, repositoryName, Optional.empty());
    }

    @Parameters({"domain", "region"})
    @Test(groups = {"canary", "codecommit-pullrequest-inference-canary"})
    public void pullRequest_WithCodeCommit_SingleSourceCommit_CMCMK(final String domain, final String region) throws Exception {
        refreshGuruClient();
        final String CodeCommitPullRequestSourceBranchName = String.format("SingleSourceCommitFeatureCMCMK-%s", System.currentTimeMillis());
        String repositoryNameCMCMK =
                String.format("CMCMK-%s-CCSingleSource-%s-%s", DELETE_INFERENCE_REPOSITORY_NAME_PREFIX, domain, region);
        codeCommitPullRequest(CodeCommitPullRequestSourceBranchName, repositoryNameCMCMK, Optional.of(createKMSKeyDetailsCmCmk()));
    }

    private void codeCommitPullRequest(String codeCommitPullRequestSourceBranchName, String repositoryName, Optional<KMSKeyDetails> keyDetails) throws
            Exception {
        CodeCommitCommiter codeCommitCommiter = new CodeCommitCommiter(this.getCodeCommitClient());
        createRepository(repositoryName);
        String associationArn = null;
        try {
            associationArn = onboard(repositoryName, keyDetails, null);
        } catch (ConflictException exception){
            log.info("Repository {} is already associated.", repositoryName);
        }
        String baseCommitId = codeCommitCommiter.createBranchesAndReturnDestinationCommit(repositoryName,
                codeCommitPullRequestSourceBranchName, DESTINATION_BRANCH_NAME);
        CreateCommitResult afterCommit = codeCommitCommiter.createAndGetAfterCommit(
                codeCommitPullRequestSourceBranchName, baseCommitId, repositoryName);
        PullRequest pullRequest = codeCommitCommiter.createPullRequest(repositoryName,
                codeCommitPullRequestSourceBranchName, DESTINATION_BRANCH_NAME);
        try {
            final List<CommentWithCategory> commentsWithCategories = waitForRecommendations(repositoryName, pullRequest.getPullRequestId(), afterCommit.getCommitId()
                    , baseCommitId);
            verifyComments(commentsWithCategories, repositoryName);

        } finally {
            if (associationArn != null) {
                disassociate(associationArn);
            }
            deleteRepository(repositoryName);
        }
    }

    @Parameters({"domain", "region"})
    @Test(groups = {"canary", "codecommit-pullrequest-inference-canary"})
    public void pullRequest_WithCodeCommit_MultipleSourceCommit(
            final String domain,
            final String region) throws Exception {
        refreshGuruClient();
        final String CodeCommitPullRequestSourceBranchName = String.format("MultipleSourceCommit-%s", System.currentTimeMillis());
        String repositoryName =
            String.format("%s-CCMultipleSource-%s-%s",
                DELETE_INFERENCE_REPOSITORY_NAME_PREFIX, domain, region);
        codeCommitPullRequest(CodeCommitPullRequestSourceBranchName, repositoryName, Optional.empty());
    }

    @BeforeClass(alwaysRun = true)
    public void setup() {
        cleanupOldRepos();
    }


    private List<CommentWithCategory> waitForRecommendations(final String repositoryName, final String pullRequestId,
                                       final String afterCommitId, final String beforeCommitId) throws
            Exception {
        log.info("Getting recommendations from code commit client for repository {}", repositoryName);
        Thread.sleep(MIN_PULL_REQUEST_DELAY_MILLIS);

        GetCommentsForPullRequestRequest getCommentsForPullRequestRequest = new GetCommentsForPullRequestRequest()
                .withRepositoryName(repositoryName)
                .withPullRequestId(pullRequestId)
                .withAfterCommitId(afterCommitId)
                .withBeforeCommitId(beforeCommitId)
                .withMaxResults(30);

        List<CommentsForPullRequest> commentsForPullRequests = new ArrayList<>();
        int count = 0;
        while (commentsForPullRequests.isEmpty() && count++ < MAX_ATTEMPTS) {
            log.info(String.format("Waiting for recommendations from Guru on CodeCommit.. Retry %d", count));
            Thread.sleep(POLLING_FREQUENCY_MILLIS);
            commentsForPullRequests = this.getCommentsForPullRequest(getCommentsForPullRequestRequest);
        }
        assertFalse(commentsForPullRequests.isEmpty(),
                String.format("No comments were published on the pull request for repo %s", repositoryName));

        //sleep so that code commit can return details about all the comments due to eventual consistency
        Thread.sleep(POLLING_FREQUENCY_MILLIS);


        return getCommentsWithCategories(getCommentsForPullRequestRequest);
    }

    private List<CommentWithCategory> getCommentsWithCategories(final GetCommentsForPullRequestRequest request)
            throws Exception {
        final List<CommentsForPullRequest> commentsForPullRequests = this.getCommentsForPullRequest(request);
        final List<CommentWithCategory> result = new ArrayList<>();
        for (final CommentsForPullRequest commentsForPullRequest : commentsForPullRequests) {
            for (final Comment comment : commentsForPullRequest.getComments()) {
                result.add(new CommentWithCategory(comment.getCommentId(), comment.getContent(), getCategory(
                        commentsForPullRequest.getLocation().getFilePath())));
            }
        }
        return result;
    }

    private List<CommentsForPullRequest> getCommentsForPullRequest(final GetCommentsForPullRequestRequest request)
            throws Exception {
        final List<CommentsForPullRequest> commentsForPullRequests = new ArrayList<>();
        // clear next token
        request.setNextToken(null);
        String nextToken = null;
        do {
            GetCommentsForPullRequestResult result = new RetryingCallable<>(RETRY_STRATEGY,
                    () -> this.getCodeCommitClient().getCommentsForPullRequest(request)).call();
            commentsForPullRequests.addAll(result.getCommentsForPullRequestData());
            nextToken = request.getNextToken();
            request.setNextToken(nextToken);
        } while (nextToken != null);
        return commentsForPullRequests;
    }
}
