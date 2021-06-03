package com.amazon.awsgurufrontendservice.pullrequest;

import com.amazon.awsgurufrontendservice.TestGitHubRepository;
import com.amazon.coral.retry.RetryStrategy;
import com.amazon.coral.retry.RetryingCallable;
import com.amazon.coral.retry.strategy.ExponentialBackoffAndJitterBuilder;
import com.amazonaws.guru.common.exceptions.GitHubApiRetryableException;
import com.amazonaws.guru.common.github.GitHubApiFacade;
import com.amazonaws.guru.common.github.GitHubPaginatedResult;
import com.amazonaws.guru.common.github.domain.GitHubCommentResponse;
import com.amazonaws.guru.common.github.domain.GitHubCreatePullRequest;
import com.amazonaws.guru.common.github.domain.GitHubPullRequest;
import com.amazonaws.guru.common.github.domain.GitHubUpdatePullRequest;
import lombok.extern.log4j.Log4j2;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertFalse;

/**
 * Responsible for raising pull requests on GitHub repositories. It calls various APIs that GitHub supports to raise
 * a pull request. It also supports raising pull requests on forked repositories.
 */
@Log4j2
public class GitHubPullRequestRaiser {
    private static final int WAIT_TIME = 2000;
    private static final float BACK_OFF_FACTOR = 0.5f;
    private static final int GITHUB_API_RETRIES = 5;
    protected static final long POLLING_FREQUENCY_MILLIS = TimeUnit.SECONDS.toMillis(60);
    protected static final int MAX_ATTEMPTS = 20;
    // No need to poll for the first X seconds - we know it won't finish faster than this.
    // Saves us some API calls.
    protected static final long MIN_PULL_REQUEST_DELAY_MILLIS = TimeUnit.MINUTES.toMillis(5);

    protected static final RetryStrategy<Void> RETRY_STRATEGY = new ExponentialBackoffAndJitterBuilder()
        .retryOn(GitHubApiRetryableException.class)
        .withMaxElapsedTimeMillis(WAIT_TIME)
        .withMaxAttempts(GITHUB_API_RETRIES)
        .withExponentialFactor(BACK_OFF_FACTOR)
        .newStrategy();

    private final TestGitHubRepository destinationRepository;
    private final TestGitHubRepository sourceRepository;
    private final GitHubApiFacade gitHubApiFacade;


    public GitHubPullRequestRaiser(final TestGitHubRepository destinationRepository,
                                   final TestGitHubRepository sourceRepository,
                                   final GitHubApiFacade gitHubApiFacade){
        this.destinationRepository = destinationRepository;
        this.sourceRepository = sourceRepository;
        this.gitHubApiFacade = gitHubApiFacade;

    }

    public GitHubApiFacade getGitHubApiFacade() {
        return gitHubApiFacade;
    }

    /**
     * Creates pull request using GitHub APIs.
     *
     * Resources http://www.levibotelho.com/development/commit-a-file-with-the-github-api/
     * https://github.com/google/go-github/blob/2399959c878412d614a8c73addc9b81e2598a0c4/example/commitpr/main.go#L62
     */
    public GitHubPullRequest raisePullRequest() throws Exception {
        // create pull request between source and forked repository
        String base = destinationRepository.getBranchName();
        String head = sourceRepository.getOwner() + ":" + sourceRepository.getBranchName();
        log.info("Raising a pull request from " + head + " to " + base);
        GitHubCreatePullRequest createPullRequest = GitHubCreatePullRequest.builder()
            .title("Pull request for recommendations")
            .body("Please provide me the recommendations")
            .base(base)
            .head(head)
            .maintainerCanModify(true)
            .build();

        final GitHubPullRequest pullRequest = (GitHubPullRequest) new RetryingCallable(RETRY_STRATEGY, () ->
            sourceRepository.getGitHubAdminApiFacade().createPullRequest(destinationRepository.getOwner(), sourceRepository.getName(), createPullRequest)).call();
        log.info("Created Pull Request " + pullRequest.getNumber());
        return pullRequest;
    }

    public GitHubPullRequest closePullRequest(final GitHubPullRequest gitHubPullRequest) {

        log.info("Closing pull request {} for repository {}. ", gitHubPullRequest.getNumber(), sourceRepository.getName());
        GitHubUpdatePullRequest closePullRequest = GitHubUpdatePullRequest.builder()
                .title("Pull request for recommendations")
                .body("Closing pull request.")
                .state("closed")
                .maintainerCanModify(true)
                .build();

        try {
            final GitHubPullRequest pullRequest = (GitHubPullRequest) new RetryingCallable(RETRY_STRATEGY, () ->
                    sourceRepository.getGitHubAdminApiFacade().updatePullRequest(
                            destinationRepository.getOwner(),
                            sourceRepository.getName(),
                            gitHubPullRequest.getNumber(),
                            closePullRequest)).call();
            log.info("Closed Pull Request {} with status {}", pullRequest.getNumber(), pullRequest.getState());
            return pullRequest;
        } catch (Exception e) {
            log.error("Failed to Close Pull Request {} for repository {}.", gitHubPullRequest.getNumber(), sourceRepository.getName());
        }
        return null;
    }

    public List<GitHubCommentResponse> waitForRecommendations(int pullNumber) throws Exception {

        Thread.sleep(MIN_PULL_REQUEST_DELAY_MILLIS);

        GitHubPaginatedResult<GitHubCommentResponse> commentsOnPullRequest =
            (GitHubPaginatedResult<GitHubCommentResponse>) new RetryingCallable(RETRY_STRATEGY, () ->
                getGitHubApiFacade().getCommentsOnPullRequest(destinationRepository.getOwner(), destinationRepository.getName(), pullNumber, null)).call();

        Thread.sleep(POLLING_FREQUENCY_MILLIS);

        int count = 0;
        while (commentsOnPullRequest.getResults().isEmpty() && count++ < MAX_ATTEMPTS) {
            log.info(String.format("Waiting for recommendations from Guru on GitHub.. Retry %d", count));
            Thread.sleep(POLLING_FREQUENCY_MILLIS);
            commentsOnPullRequest =
                (GitHubPaginatedResult<GitHubCommentResponse>) new RetryingCallable(RETRY_STRATEGY, () ->
                    getGitHubApiFacade().getCommentsOnPullRequest(destinationRepository.getOwner(), destinationRepository.getName(), pullNumber, null)).call();
        }

        assertFalse(commentsOnPullRequest.getResults().isEmpty(),
            String.format("No comments were published on the pull request for repository %s", destinationRepository.getName()));

        final List<GitHubCommentResponse> result = new ArrayList<>(commentsOnPullRequest.getResults());
        while (commentsOnPullRequest.getNextToken() != null) {
            final GitHubPaginatedResult<GitHubCommentResponse> finalCommentsOnPullRequest = commentsOnPullRequest;
            commentsOnPullRequest = (GitHubPaginatedResult<GitHubCommentResponse>) new RetryingCallable(RETRY_STRATEGY,
                () -> getGitHubApiFacade().getCommentsOnPullRequest(destinationRepository.getOwner(), destinationRepository.getName(),
                    pullNumber, finalCommentsOnPullRequest.getNextToken())).call();
            result.addAll(commentsOnPullRequest.getResults());
        }

        return result;
    }


}
