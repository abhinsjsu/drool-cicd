package com.amazon.awsgurufrontendservice;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.codecommit.AWSCodeCommit;
import com.amazonaws.services.codecommit.AWSCodeCommitClientBuilder;
import com.amazonaws.services.codecommit.model.CreateRepositoryRequest;
import com.amazonaws.services.codecommit.model.DeleteRepositoryRequest;
import com.amazonaws.services.codecommit.model.GetRepositoryRequest;
import com.amazonaws.services.codecommit.model.ListRepositoriesRequest;
import com.amazonaws.services.codecommit.model.OrderEnum;
import com.amazonaws.services.codecommit.model.RepositoryNameExistsException;
import com.amazonaws.services.codecommit.model.RepositoryNameIdPair;
import com.amazonaws.services.codecommit.model.SortByEnum;
import com.amazonaws.services.gurufrontendservice.model.CodeCommitRepository;
import com.amazonaws.services.gurufrontendservice.model.KMSKeyDetails;
import com.amazonaws.services.gurufrontendservice.model.Repository;
import lombok.extern.log4j.Log4j2;

import java.util.Map;
import java.util.Optional;

@Log4j2
public class CodeCommitTestSuite extends IntegrationTestBase {

    private final AWSCodeCommit codeCommitClient;
    public static final String DESTINATION_BRANCH_NAME = "master";


    public CodeCommitTestSuite(final String domain, final String region) {
        super(domain, region);
        this.codeCommitClient = AWSCodeCommitClientBuilder.standard()
                                                          .withRegion(Regions.fromName(region))
                                                          .withCredentials(getCredentials())
                                                          .build();
    }

    public AWSCodeCommit getCodeCommitClient() {
        return codeCommitClient;
    }

    public String onboard(final String repositoryName, final Map<String, String> tags) throws InterruptedException {
        return onboard(repositoryName, Optional.empty(), tags);
    }

    public String onboard(final String repositoryName, final Optional<KMSKeyDetails> kmsKeyDetails, final Map<String, String> tags) throws InterruptedException {
        Repository repository = new Repository().withCodeCommit(
            new CodeCommitRepository().withName(repositoryName));

        return onboardBase(repositoryName, repository, kmsKeyDetails, tags).getRepositoryAssociation().getAssociationArn();
    }

    public void createRepository(final String repositoryName) {
        try {
            CreateRepositoryRequest createRepositoryRequest = new
                CreateRepositoryRequest().withRepositoryName(repositoryName);
            codeCommitClient.createRepository(createRepositoryRequest);
        } catch (RepositoryNameExistsException exception) {
            log.info("Repository already exists");
        }
    }

    public void deleteRepository(final String repositoryName) {
        log.info(String.format("Deleting repository %s", repositoryName));
        DeleteRepositoryRequest deleteRepositoryRequest = new
            DeleteRepositoryRequest().withRepositoryName(String.format(repositoryName));
        getCodeCommitClient().deleteRepository(deleteRepositoryRequest);
    }

    // Cleanup old repos left over from previous executions.
    // Tests are supposed to cleanup after themselves, but sometimes these cleanups fail.
    // This is just an additional protection.
    public void cleanupOldRepos() {
        log.info("Cleaning up old repos");
        int deleted = 0;
        try {
            // Not bothering to paginate the results as we are going to delete only a very few
            // repos at a time.
            for (final RepositoryNameIdPair nameIdPair : getCodeCommitClient().listRepositories(
                    new ListRepositoriesRequest()
                    .withSortBy(SortByEnum.LastModifiedDate) // Oldest repositories first
                    .withOrder(OrderEnum.Ascending)).getRepositories()) {


                final long lastModifiedTimestamp = getCodeCommitClient()
                        .getRepository(new GetRepositoryRequest().withRepositoryName(nameIdPair.getRepositoryName()))
                        .getRepositoryMetadata()
                        .getLastModifiedDate()
                        .getTime();

                final long age = System.currentTimeMillis() - lastModifiedTimestamp;
                if (age > AGE_TO_CLEANUP_MILLISECONDS) {
                    deleteRepository(nameIdPair.getRepositoryName());
                    deleted++;
                    if (deleted >= MAX_REPOS_TO_CLEANUP) {
                        break;
                    }
                } else {
                    // Repos are sorted by last modified date.
                    // If this is not old enough, neither are the subsequent repos.
                    break;
                }
            }
        } catch (Exception e) {
            // Swallowing all errors - the cleanup is not supposed to fail the tests.
            // Errors can be completely benign, e.g. several threads cleaning up the same repo.
            log.info("Error cleaning up old repos: " + e);
        } finally {
            log.info(String.format("Done cleaning up old repos - deleted %s repos", deleted));
        }
    }
}
