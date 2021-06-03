package com.amazon.awsgurufrontendservice.bitbucket;

import com.amazon.awsgurufrontendservice.IntegrationTestBase;
import com.amazon.awsgurufrontendservice.utils.CodeConnectHelper;
import com.amazon.awsgurufrontendservice.utils.CredentialsHelper;
import com.amazonaws.guru.common.bitbucket.BitbucketAdminApiFacade;
import com.amazonaws.guru.common.bitbucket.BitbucketApiFacadeFactory;
import com.amazonaws.guru.common.bitbucket.domain.BitbucketCreateCommitRequest;
import com.amazonaws.guru.common.bitbucket.domain.BitbucketCreatePullRequestRequest;
import com.amazonaws.guru.common.bitbucket.domain.BitbucketCreateRepositoryRequest;
import com.amazonaws.guru.common.bitbucket.domain.BitbucketPullRequest;
import com.amazonaws.guru.common.bitbucket.domain.BitbucketPullRequestRequestSource;
import com.amazonaws.guru.common.bitbucket.domain.BitbucketRepository;
import com.amazonaws.guru.common.exceptions.BitbucketResourceNotFoundException;
import com.amazonaws.services.gurufrontendservice.model.AssociateRepositoryResult;
import com.amazonaws.services.gurufrontendservice.model.KMSKeyDetails;
import com.amazonaws.services.gurufrontendservice.model.Repository;
import com.amazonaws.services.gurufrontendservice.model.ThirdPartySourceRepository;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.log4j.Log4j2;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Log4j2
public class BitbucketTestSuite extends IntegrationTestBase {
    protected final BitbucketAdminApiFacade bitbucketAdminApiFacade;
    protected final CodeConnectHelper codeConnectHelper;

    private static final String BB_REPOSITORY_ALREADY_CREATED = "You already have a repository with this name";
    protected final static String DEFAULT_DESTINATION_BRANCH_NAME = "master";
    protected final static String INITIAL_FILE_TO_COMMIT = "tst-resource/repository/InitialCommitFile.java";

    private static final String PRECREATED_REPO_PREFIX = "do-not-delete";


    protected final Credentials credential;

    public BitbucketTestSuite(final String domain, final String region, final String bitbucketSecretId) {
        super(domain, region);
        this.codeConnectHelper = new CodeConnectHelper(domain, region);
        this.credential = CredentialsHelper.getCredential(bitbucketSecretId, region, Credentials.class);

        BitbucketApiFacadeFactory bitbucketApiFacadeFactory = new BitbucketApiFacadeFactory(buildHttpClient());
        this.bitbucketAdminApiFacade = bitbucketApiFacadeFactory.createAdminApiFacade(
                this.credential.getUsername(),
                this.credential.getBasicAuthHeader()
        );
    }

    protected String getUsername() {
        return credential.getUsername();
    }

    protected AssociateRepositoryResult onboard(final String repositoryName, final String owner) throws InterruptedException {
        return onboard(repositoryName, owner, Optional.empty());
    }

    protected AssociateRepositoryResult onboard(final String repositoryName, final String owner, final Optional<KMSKeyDetails> kmsKeyDetails) throws InterruptedException {
        final String connectionArn = credential.getConnectionArn();
        final Repository repository = new Repository().withBitbucket(
                new ThirdPartySourceRepository()
                        .withOwner(owner)
                        .withName(repositoryName)
                        .withConnectionArn(connectionArn)
        );

        return onboardBase(repositoryName, repository, kmsKeyDetails, null);
    }

    protected void createRepository(final String repositoryName) {
        final String SCM = "git";
        BitbucketCreateRepositoryRequest createRepositoryRequest =
                new BitbucketCreateRepositoryRequest(repositoryName, SCM);
        final BitbucketRepository bitbucketRepository = this.bitbucketAdminApiFacade.createRepository(
                getUsername(),
                createRepositoryRequest
        );
        log.info("Created Bitbucket repository: {} ", bitbucketRepository.getFullName());
    }

    protected void deleteRepository(final String repositoryName) {
        if (!repositoryName.startsWith(PRECREATED_REPO_PREFIX)) {
            try {
                this.bitbucketAdminApiFacade.deleteRepository(getUsername(), repositoryName);
                log.info("Deleted repository {}", repositoryName);
            } catch (BitbucketResourceNotFoundException e) {
                log.info("Skipping delete as repository = {} does not exist", repositoryName);
            }
        }
    }

    protected BitbucketPullRequest createPullRequest(final String repositoryName, final String branchName, final int numOfCommits, final String title) {


        commitTestFiles(repositoryName, branchName, numOfCommits);
        BitbucketCreatePullRequestRequest requestRequest = BitbucketCreatePullRequestRequest
                .builder()
                .title(title)
                .source(BitbucketPullRequestRequestSource.builder()
                        .branch(branchName)
                        .build())
                .build();

        return bitbucketAdminApiFacade.createPullRequest(credential.getUsername(),
                repositoryName,
                requestRequest);
    }

    protected void commitTestFiles(
            @NonNull final String repositoryName,
            @NonNull final String branchName,
            final int numOfCommits) {

        Map<String, File> fileEntryMap = getFilesToBeAddedInPullRequest();
        List<File> files = new LinkedList<>();
        for (List<String> fileNames : IntegrationTestBase.FILES_IN_EACH_COMMIT) {
            files.addAll(fileNames.stream().map(fileEntryMap::get).collect(Collectors.toList()));

            // If the number of commits is 1 we don't want to make a commit right now and keep adding
            // files so that they can be committed together
            if (numOfCommits == 1){
                continue;
            }

            // If the number of commits is more than 1 (i.e. we are testing a multi-commit scenario)
            // then we create a commit in each loop iteration with the corresponding files in that commit
            createSourceCommit(files, branchName, repositoryName);
        }
        // Commit all the files in a single commit
        if(numOfCommits == 1){
            createSourceCommit(files, branchName, repositoryName);
        }

    }

    protected void createFirstCommit(final String repositoryName, final String initialFilePath, final String branchName) {
        final Path initialFile = Paths.get(
                getRealPath(initialFilePath));
        final List<File> files = new LinkedList<>();
        files.add(initialFile.toFile());

        final BitbucketCreateCommitRequest createCommitRequest =  BitbucketCreateCommitRequest.builder()
                .files(files)
                .branchName(branchName)
                .build();

        bitbucketAdminApiFacade.createCommit(credential.getUsername(), repositoryName, createCommitRequest);
    }

    private void createSourceCommit(List<File> files, String branchName, String repositoryName) {
        BitbucketCreateCommitRequest sourceCommit = BitbucketCreateCommitRequest
                .builder()
                .files(files)
                .branchName(branchName)
                .build();
        bitbucketAdminApiFacade.createCommit(credential.getUsername(), repositoryName, sourceCommit);
    }

    private Map<String, File> getFilesToBeAddedInPullRequest() {
        String repositoryPath = "tst-resource/repository";
        Map<String, File> fileNameMap = new HashMap<>();
        try {
            Files.walk(Paths.get(getRealPath(repositoryPath)))
                    .filter(Files::isRegularFile)
                    .forEach(file -> {
                        final String path = String.format("%s/%s",
                                repositoryPath,
                                Paths.get(getRealPath(repositoryPath)).relativize(file).toFile().getPath());
                        fileNameMap.put(file.getFileName().toString(), Paths.get(getRealPath(path)).toFile());
                    });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return fileNameMap;
    }

    @Getter
    @AllArgsConstructor
    public static class Credentials {
        private String username;
        private String basicAuthHeader;
        private String connectionArn;
    }

}