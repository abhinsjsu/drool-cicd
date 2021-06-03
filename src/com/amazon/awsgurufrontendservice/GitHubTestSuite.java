package com.amazon.awsgurufrontendservice;

import com.amazon.coral.internal.netty4.io.netty.handler.codec.http.HttpStatusClass;
import com.amazon.coral.retry.RetryStrategy;
import com.amazon.coral.retry.RetryingCallable;
import com.amazon.coral.retry.strategy.ExponentialBackoffAndJitterBuilder;
import com.amazonaws.guru.common.exceptions.GitHubApiException;
import com.amazonaws.guru.common.exceptions.GitHubApiRetryableException;
import com.amazonaws.guru.common.exceptions.NotFoundException;
import com.amazonaws.guru.common.github.*;
import com.amazonaws.guru.common.github.domain.GitHubRepo;
import com.amazonaws.services.gurufrontendservice.model.AuthorizationToken;
import com.amazonaws.services.gurufrontendservice.model.CreateConnectionTokenRequest;
import com.amazonaws.services.gurufrontendservice.model.CreateConnectionTokenResult;
import com.amazonaws.services.gurufrontendservice.model.GitHubRepository;
import com.amazonaws.services.gurufrontendservice.model.KMSKeyDetails;
import com.amazonaws.services.gurufrontendservice.model.ListThirdPartyRepositoriesRequest;
import com.amazonaws.services.gurufrontendservice.model.ListThirdPartyRepositoriesResult;
import com.amazonaws.services.gurufrontendservice.model.ProviderType;
import com.amazonaws.services.gurufrontendservice.model.Repository;
import com.amazonaws.services.gurufrontendservice.model.ThirdPartyRepository;
import com.amazonaws.services.secretsmanager.AWSSecretsManager;
import com.amazonaws.services.secretsmanager.AWSSecretsManagerClientBuilder;
import com.amazonaws.services.secretsmanager.model.GetSecretValueRequest;
import com.amazonaws.services.secretsmanager.model.GetSecretValueResult;
import com.google.gson.Gson;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang.StringUtils;
import org.apache.http.impl.client.CloseableHttpClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.testng.Assert.fail;

@Log4j2
public class GitHubTestSuite extends IntegrationTestBase {
    private static final Duration WAIT_TIME = Duration.ofSeconds(2);

    private static final Duration WAIT_TIME_5X = WAIT_TIME.multipliedBy(5);
    private static final float BACK_OFF_FACTOR = 0.5f;
    private static final int GITHUB_API_RETRIES = 5;

    // Adding 1 minute wait time after creating Github repository.
    // Arbitrary value will have to observe if it solves problem.
    private static final long GITHUB_PROPAGATION_WAIT_TIME = TimeUnit.MINUTES.toMillis(1);
    protected static final RetryStrategy<Void> RETRY_STRATEGY = new ExponentialBackoffAndJitterBuilder()
        .retryOn(GitHubApiRetryableException.class)
        .withMaxElapsedTimeMillis(WAIT_TIME.toMillis())
        .withMaxAttempts(GITHUB_API_RETRIES)
        .withExponentialFactor(BACK_OFF_FACTOR)
        .newStrategy();
    // This strategy is specifically used to retry till a resource is available in GitHub
    protected static final RetryStrategy<Void> RETRY_STRATEGY_FOR_NOT_FOUND = new ExponentialBackoffAndJitterBuilder()
            .retryOn(GitHubApiRetryableException.class, NotFoundException.class)
            .withMaxElapsedTimeMillis(WAIT_TIME_5X.toMillis())
            .withInitialIntervalMillis(1000)
            .withMaxAttempts(GITHUB_API_RETRIES)
            .withRandomizationFactor(0.2d)
            .withExponentialFactor(BACK_OFF_FACTOR)
            .newStrategy();
    private final GitHubAdminApiFacade gitHubAdminApiFacade;
    private final Optional<GitHubAdminApiFacade> gitHubAdminApiForkedRepoFacade;
    private final Credentials credentials;
    private final Optional<Credentials> credentialsForkedRepo;
    private final GitHubApiFacade gitHubApiFacade;

    public GitHubTestSuite(final String domain, final String region, final String secretId,
                           final String secretIdForkedRepo) {
        super(domain, region);
        log.info("Running with {} and {}", domain, region);
        CloseableHttpClient httpClient = buildHttpClient();

        GitHubApiFacadeFactory gitHubApiFacadeFactory = new GitHubApiFacadeFactory(httpClient);
        if (StringUtils.isNotBlank(secretId)) {
            AWSSecretsManager client = AWSSecretsManagerClientBuilder.standard()
                .withRegion(region)
                .build();

            GetSecretValueRequest getSecretValueRequest = new GetSecretValueRequest()
                .withSecretId(secretId);
            final GetSecretValueResult getSecretValueResult = client.getSecretValue(getSecretValueRequest);

            // Decrypts secret using the associated KMS CMK.
            // Depending on whether the secret is a string or binary, one of these fields will be populated.
            if (getSecretValueResult.getSecretString() != null) {
                final Gson gson = new Gson();
                this.credentials = gson.fromJson(getSecretValueResult.getSecretString(), Credentials.class);
            } else {
                throw new RuntimeException("The github secretId are null.");
            }
        } else {
            throw new RuntimeException("A secretId for Github credentials must be provided to run Github tests. This" +
                                       "should be formatted as {owner: GITHUB_USERNAME, token: PERSONAL_ACCESS_TOKEN}");
        }

        if (StringUtils.isNotBlank(secretIdForkedRepo)) {
            AWSSecretsManager client = AWSSecretsManagerClientBuilder.standard()
                .withRegion(region)
                .build();

            GetSecretValueRequest getSecretValueRequest = new GetSecretValueRequest()
                .withSecretId(secretIdForkedRepo);
            final GetSecretValueResult getSecretValueResult = client.getSecretValue(getSecretValueRequest);

            // Decrypts secret using the associated KMS CMK.
            // Depending on whether the secret is a string or binary, one of these fields will be populated.
            if (getSecretValueResult.getSecretString() != null) {
                final Gson gson = new Gson();
                this.credentialsForkedRepo = Optional.of(gson.fromJson
                    (getSecretValueResult.getSecretString(), Credentials.class));
                this.gitHubAdminApiForkedRepoFacade = Optional.of(gitHubApiFacadeFactory.createAdminApiFacade
                    (this.credentialsForkedRepo.get().token));
            } else {
                throw new RuntimeException("The github secretIdForkedRepo are null.");
            }
        } else {
            this.credentialsForkedRepo = Optional.empty();
            this.gitHubAdminApiForkedRepoFacade = Optional.empty();
            log.info("Credential for forked repository are not available, so forked repository inference tests will" +
                " be disabled.");
        }

        this.gitHubAdminApiFacade = gitHubApiFacadeFactory.createAdminApiFacade(this.credentials.token);
        this.gitHubApiFacade = gitHubApiFacadeFactory.createApiFacade(this.credentials.token);

    }

    protected String getOwner() {
        return credentials.owner;
    }

    protected String getForkedRepoOwner() {
        return credentialsForkedRepo.get().owner;
    }

    protected GitHubAdminApiFacade getGitHubAdminApiFacade() {
        return gitHubAdminApiFacade;
    }

    protected GitHubAdminApiFacade getGitHubAdminApiForkedRepoFacade() {
        return gitHubAdminApiForkedRepoFacade.get();
    }

    protected GitHubApiFacade getGitHubApiFacade() {
        return gitHubApiFacade;
    }

    protected CreateConnectionTokenResult fetchConnectionToken(){
        return fetchConnectionToken(credentials.token, "admin", "guru-dev");
    }

    protected boolean isForkedRepoCredentialsAvailable(){
        return this.credentialsForkedRepo.isPresent();
    }

    protected CreateConnectionTokenResult fetchConnectionToken(String token, String scope, String user){
        AuthorizationToken authToken = new AuthorizationToken()
            .withToken(token)
            .withCreationTime(Date.from(Instant.now()))
            .withScopes(scope)
            .withUser(user);
        CreateConnectionTokenRequest createTokenRequest = new CreateConnectionTokenRequest()
            .withProviderType(ProviderType.GitHub)
            .withAuthToken(authToken);
        CreateConnectionTokenResult connectionToken = getGuruClient().createConnectionToken(createTokenRequest);
        return connectionToken;
    }

    protected void verifyRepositoryIsVisible(
            final String expectedRepository, final CreateConnectionTokenResult connectionToken)
            throws InterruptedException {

        // Repository creation can be eventually consistent, so we try several times.
        // Almost always succeeds at the first try.
        // TODO: use Coral Retry instead
        final int MAX_RETRIES = 6;
        final int DELAY_MILLISECONDS = 10_000;

        List<String> visibleRepositories = new ArrayList<>();

        for (int retry = 1; retry <= MAX_RETRIES; retry++) {
            Thread.sleep(DELAY_MILLISECONDS);
            log.info(String.format(
                    "Checking repository %s is visible, attempt %d/%d", expectedRepository, retry, MAX_RETRIES));

            visibleRepositories = new ArrayList<>();
            String nextToken = null;
            do {
                ListThirdPartyRepositoriesRequest list3pRepositories = new ListThirdPartyRepositoriesRequest()
                        .withConnectionToken(connectionToken.getConnectionToken())
                        .withNextToken(nextToken);

                ListThirdPartyRepositoriesResult listThirdPartyRepositoriesResult =
                        getGuruClient().listThirdPartyRepositories(list3pRepositories);

                List<String> repoNamesInPage = listThirdPartyRepositoriesResult.getThirdPartyRepositories().stream()
                        .map(ThirdPartyRepository::getName).collect(Collectors.toList());
                if (repoNamesInPage.stream().anyMatch(name -> name.equalsIgnoreCase(expectedRepository))) {
                    log.info(String.format("Repository %s is visible", expectedRepository));
                    return;
                }
                visibleRepositories.addAll(repoNamesInPage);
                nextToken = listThirdPartyRepositoriesResult.getNextToken();
            } while (nextToken != null);
        }

        fail(String.format(
                "Repository %s is not visible via listThirdPartyRepositories. Visible repositories: %s",
                expectedRepository, visibleRepositories));
    }

    protected String onboard(final String repositoryName, final String owner,
                             final String accessToken) throws InterruptedException {

        return onboard(repositoryName, owner, accessToken, Optional.empty());
    }

    protected String onboard(final String repositoryName, final String owner,
                             final String accessToken, final Optional<KMSKeyDetails> kmsKeyDetails) throws InterruptedException {
        Repository repository = new Repository().withGitHub(
            new GitHubRepository()
                .withOwner(owner)
                .withAccessToken(accessToken)
                .withName(repositoryName));

        return onboardBase(repositoryName, repository, kmsKeyDetails, null).getRepositoryAssociation().getAssociationArn();
    }

    protected void createRepository(final String repositoryName) throws Exception {
        final GitHubCreateRepositoryRequest createRepositoryRequest =
                new GitHubCreateRepositoryRequest(repositoryName, "Test", "github.com", true, true);

        final GitHubRepo gitHubRepo =
                (GitHubRepo) new RetryingCallable(RETRY_STRATEGY,() -> this.gitHubAdminApiFacade.
                        createRepository(credentials.getOwner(), createRepositoryRequest)).call();
        log.info("Created GitHub repository: {} ", gitHubRepo.getFullName());

        GitHubRepo createdRepo = (GitHubRepo) new RetryingCallable(RETRY_STRATEGY_FOR_NOT_FOUND, () -> {
            try {
                return this.gitHubApiFacade.getRepository(credentials.getOwner(), gitHubRepo.getName());
            } catch (GitHubApiException e) {
                if (HttpStatusClass.valueOf(e.getStatusCode()) == HttpStatusClass.SERVER_ERROR) {
                    log.warn("HTTP request for getRepository API got response code {}: {}", e.getStatusCode(), e.getMessage());
                    throw new GitHubApiRetryableException(e.getMessage(), e.getStatusCode());
                } else {
                    throw e;
                }
            }
        }).call();
        log.info("Created GitHub repository is available in GitHub: {} ", createdRepo.getFullName());

        Thread.sleep(GITHUB_PROPAGATION_WAIT_TIME);
    }

    protected void deleteRepository(final String repositoryName) throws Exception {
        log.info(String.format("Deleting repository %s", repositoryName));
        try {
            new RetryingCallable(RETRY_STRATEGY, () -> {
                this.gitHubAdminApiFacade.deleteRepository(credentials.owner, repositoryName);
                return null; // doesn't matter what we return here.
            }).call();
        } catch (NotFoundException e) { // 404
            log.info("Skipping delete as repository={} do not exist", repositoryName);
        }
    }

    // Cleanup old repos left over from previous executions.
    // Tests are supposed to cleanup after themselves, but sometimes these cleanups fail.
    // This is just an additional protection.
    protected void cleanupOldRepos() {
        log.info("Cleaning up old repos");
        int deleted = 0;
        List<GitHubRepo> reposToDelete = new LinkedList();
        try {
            // Not bothering to paginate the results as we are going to delete only a very few
            // repos at a time.
            String nextToken = null;

            do {
                final String finalNextToken = nextToken;
                GitHubPaginatedResult<GitHubRepo> paginatedResult =
                    (GitHubPaginatedResult<GitHubRepo>) new RetryingCallable(RETRY_STRATEGY,
                        () -> gitHubApiFacade.listRepositories(finalNextToken)).call();
                reposToDelete.addAll(paginatedResult.getResults().stream()
                        .filter(repo -> Instant.now().minusMillis(AGE_TO_CLEANUP_MILLISECONDS)
                                .isAfter(repo.getCreatedAt().toInstant()))
                        // do not delete repositories that are setup for forked repositories 
                        .filter(repo -> ! (repo.getName().startsWith(GITHUB_RECOMMENDATIONFEEDBACK_REPOSITORY_NAME_PREFIX)) )
                        .collect(Collectors.toList()));
                if (reposToDelete.size() >= MAX_REPOS_TO_CLEANUP) {
                    break;
                }
                nextToken = paginatedResult.getNextToken();
            } while (nextToken != null);

            for (final GitHubRepo repo : reposToDelete) {
                try {
                    deleteRepository(repo.getName());
                    deleted++;
                } catch (Exception exception) {
                    log.info(String.format("Can't delete repository %s - %s", repo.getName(), exception.getMessage()));
                }
            }
        } catch (Exception e) {
            // Swallowing all errors - the cleanup is not supposed to fail the tests.
            // Errors can be completely benign, e.g. several threads cleaning up the same repo.
            log.info("Error cleaning up old repos: " + e);
        } finally {
            log.info(String.format("Done cleaning up old repos - tried deleting %d repos" +
                    ", successfully deleted %d repos", reposToDelete.size(), deleted));
        }
    }

    @Getter
    @AllArgsConstructor
    private static class Credentials {
        private String owner;
        private String token;
    }
}
