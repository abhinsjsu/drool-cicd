package com.amazon.awsgurufrontendservice.githubenterprise;

import com.amazon.awsgurufrontendservice.utils.CodeConnectHelper;
import com.amazon.coral.retry.RetryingCallable;
import com.amazon.awsgurufrontendservice.IntegrationTestBase;
import com.amazon.awsgurufrontendservice.utils.CredentialsHelper;
import com.amazonaws.guru.common.exceptions.NotFoundException;
import com.amazonaws.guru.common.github.GitHubAdminApiFacade;
import com.amazonaws.guru.common.github.GitHubApiFacade;
import com.amazonaws.guru.common.github.GitHubApiFacadeFactory;
import com.amazonaws.guru.common.github.GitHubCreateRepositoryRequest;
import com.amazonaws.guru.common.github.domain.GitHubRepo;
import com.amazonaws.services.gurufrontendservice.model.AssociateRepositoryResult;
import com.amazonaws.services.gurufrontendservice.model.ConflictException;
import com.amazonaws.services.gurufrontendservice.model.DescribeRepositoryAssociationResult;
import com.amazonaws.services.gurufrontendservice.model.KMSKeyDetails;
import com.amazonaws.services.gurufrontendservice.model.Repository;
import com.amazonaws.services.gurufrontendservice.model.ThirdPartySourceRepository;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang.StringUtils;

import java.util.Optional;

@Log4j2
public class GitHubEnterpriseTestSuite extends IntegrationTestBase {

    protected final GitHubAdminApiFacade gitHubEnterpriseAdminApiFacade;
    protected final GitHubApiFacade gitHubEnterpriseApiFacade;
    protected final CodeConnectHelper codeConnectHelper;

    protected final Credentials credential;

    // All GHE repositories without 'e2e-' prefix will be deleted after 15 minutes:
    // https://quip-amazon.com/CPPEAeyKob6d/Consolidated-GHE-Cluster
    // We will add this prefix to GHE repository names to enable those repositories to be re-used across
    // integration test runs.
    protected static final String PERSISTENT_INFERENCE_REPOSITORY_NAME_PREFIX = "e2e";

    public GitHubEnterpriseTestSuite(final String domain, final String region, final String gitHubEnterpriseSecretId) {
        super(domain, region);

        // Necessary because this class and its subclasses are instantiated even when not passed as a group
        if (StringUtils.isBlank(gitHubEnterpriseSecretId)) {
            log.info("Missing credential for gitHubEnterpriseSecretId, so tests will be disabled.");
            this.codeConnectHelper = null;
            this.credential = null;
            this.gitHubEnterpriseAdminApiFacade = null;
            this.gitHubEnterpriseApiFacade = null;
            return;
        }

        this.codeConnectHelper = new CodeConnectHelper(domain, region);
        this.credential = CredentialsHelper.getCredential(gitHubEnterpriseSecretId, region, Credentials.class);

        GitHubApiFacadeFactory gitHubEnterpriseApiFacadeFactory = new GitHubApiFacadeFactory(buildHttpClient());

        this.gitHubEnterpriseAdminApiFacade = gitHubEnterpriseApiFacadeFactory.createAdminApiFacade(
                this.credential.getToken(),
                this.credential.getHostName()
        );

        this.gitHubEnterpriseApiFacade = gitHubEnterpriseApiFacadeFactory.createApiFacade(
                this.credential.getToken(),
                null,
                this.credential.getHostName()
        );
    }

    /**
     * Onboards a repository and catches ConflictException in case the repository has already been onboarded.
     * If the repository has not been onboarded (or if the repository has been disassociated), the DynamoDB cache
     * that stores the Association ARN for the repository is updated. This enables repositories to be
     * re-used between test runs without needing to be re-onboarded
     * @param repositoryName Name of git repository
     * @param dynamoDbCacheKey Key for Dynamo DB entry that contains the entry for repositoryName's Association ID
     * @param kmsKeyDetail Credentials used to access git repository
     * @return ARN of the association
     * @throws InterruptedException
     */
    protected String onboardRepositoryIfNotOnboarded(final String repositoryName, final String dynamoDbCacheKey, java.util.Optional<KMSKeyDetails> kmsKeyDetail) throws InterruptedException {
        String associationId;
        try {
            log.info("Trying to onboard GitHubEnterprise Repository {} and update Dynamo DB cache key {}.", repositoryName, dynamoDbCacheKey);
            onboard(repositoryName, getOwner(), kmsKeyDetail).getRepositoryAssociation();
            log.info("Onboarded GitHubEnterprise Repository {}. Will now invalidate Dynamo DB cache key {}", repositoryName, dynamoDbCacheKey);
            // If we have onboarded a repo, we should invalidate the cache because it should have a new association
            invalidateDynamoDBCache(dynamoDbCacheKey);
        } catch(ConflictException e) {
            log.info("GitHubEnterprise Repository {} already onboarded. We will retrieve Association ID from Dynamo DB cache key {}", repositoryName, dynamoDbCacheKey);
        }
        log.info("Retrieving Association for GitHubEnterprise Repository {} from Dynamo DB cache key {}", repositoryName);
        DescribeRepositoryAssociationResult associationResult = fetchRepositoryAssociationResult(repositoryName, dynamoDbCacheKey);
        associationId = associationResult.getRepositoryAssociation().getAssociationId();
        log.info("Successfully retrieved Association {} for GitHubEnterprise Repository {} from Dynamo DB cache key {}", repositoryName, associationId, dynamoDbCacheKey);
        return associationResult.getRepositoryAssociation().getAssociationArn();
    }

    protected AssociateRepositoryResult onboard(final String repositoryName, final String owner) throws InterruptedException {
        return onboard(repositoryName, owner, Optional.empty());
    }

    protected AssociateRepositoryResult onboard(final String repositoryName, final String owner, final Optional<KMSKeyDetails> kmsKeyDetail) throws InterruptedException {
        final Repository repository = new Repository().withGitHubEnterpriseServer(
            new ThirdPartySourceRepository()
                .withOwner(owner)
                .withName(repositoryName)
                .withConnectionArn(credential.getConnectionArn())
        );
        return onboardBase(repositoryName, repository, kmsKeyDetail, null);
    }

    /**
     * Attempts to create a new repository and will catch exceptions related to the repository already existing.
     * This enables repositories to be re-used between test runs without needing to be re-created
     * @param repositoryName name of git repository
     * @throws Exception will throw exceptions not related to a repository already existing
     */
    protected void createRepositoryIfNotExists(String repositoryName) throws Exception {
        try {
            log.info("Trying to create repository {}", repositoryName);
            createRepository(repositoryName);
            log.info("Successfully created GitHubEnterprise Repository {} as it did not exist.", repositoryName);
        } catch (Exception e) {
            if(!e.toString().contains(GITHUB_REPOSITORY_ALREADY_CREATED)) {
                log.warn("Unexpected exception thrown when trying to create GitHubEnterprise repository {}", repositoryName);
                throw e;
            }
            log.info("GitHubEnterprise Repository {} already exists.", repositoryName);
        }
    }

    protected void createRepository(final String repositoryName) throws Exception {

        final GitHubCreateRepositoryRequest createRepositoryRequest =
                new GitHubCreateRepositoryRequest(repositoryName, "Test", "github.com", true, true);

        final GitHubRepo gitHubEnterpriseRepository =
                (GitHubRepo) new RetryingCallable(RETRY_STRATEGY,() -> this.gitHubEnterpriseAdminApiFacade.
                        createRepository(credential.getOwner(), createRepositoryRequest)).call();
        log.info("Created GitHubEnteprise repository: {} ", gitHubEnterpriseRepository.getFullName());
    }

    protected void deleteRepository(final String repositoryName) throws Exception {
        log.info(String.format("Deleting repository %s", repositoryName));
        try {
            new RetryingCallable(RETRY_STRATEGY, () -> {
                this.gitHubEnterpriseAdminApiFacade.deleteRepository(credential.owner, repositoryName);
                return null; // doesn't matter what we return here.
            }).call();
        } catch (NotFoundException e) { // 404
            log.info("Skipping delete as repository={} does not exist", repositoryName);
        }
    }

    /**
     * Delete branch after an integration test has completed
     * @param repositoryName Name of the repository in which a branch will be deleted
     * @param branchName Name of branch to be deleted
     * @throws Exception will throw exceptions not related to a branch not existing
     */
    protected void deleteBranch(final String repositoryName, final String branchName) throws Exception {
        log.info("Deleting branch {} in repository {}", branchName, repositoryName);
        try {
            new RetryingCallable(RETRY_STRATEGY, () -> {
                this.gitHubEnterpriseAdminApiFacade.deleteBranch(getOwner(), repositoryName, branchName);
                return null;
            }).call();
        } catch (Exception e) {
            if (!e.toString().contains(GITHUB_BRANCH_NOT_FOUND)) {
                log.warn("Unexpected exception thrown when trying to delete Branch {} from Repository {}", branchName, repositoryName);
                throw e;
            }
            log.info("Skipping delete as branch {} in repository {} does not exist", branchName, repositoryName);
        }
    }

    @Getter
    @AllArgsConstructor
    public static class Credentials {
        private String owner;
        private String token;
        private String connectionArn;
        private String hostName;
    }

    protected String getOwner() {return this.credential.getOwner(); }

}