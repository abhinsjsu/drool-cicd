package com.amazon.awsgurufrontendservice.codecommit;


import com.amazonaws.services.codecommit.AWSCodeCommit;
import com.amazonaws.services.codecommit.model.BranchDoesNotExistException;
import com.amazonaws.services.codecommit.model.CreateBranchRequest;
import com.amazonaws.services.codecommit.model.CreateCommitRequest;
import com.amazonaws.services.codecommit.model.CreateCommitResult;
import com.amazonaws.services.codecommit.model.CreatePullRequestRequest;
import com.amazonaws.services.codecommit.model.DefaultBranchCannotBeDeletedException;
import com.amazonaws.services.codecommit.model.DeleteBranchRequest;
import com.amazonaws.services.codecommit.model.DeleteBranchResult;
import com.amazonaws.services.codecommit.model.GetBranchRequest;
import com.amazonaws.services.codecommit.model.GetBranchResult;
import com.amazonaws.services.codecommit.model.PullRequest;
import com.amazonaws.services.codecommit.model.PullRequestStatusEnum;
import com.amazonaws.services.codecommit.model.PutFileEntry;
import com.amazonaws.services.codecommit.model.Target;
import com.amazonaws.services.codecommit.model.UpdatePullRequestStatusRequest;
import com.amazonaws.services.codecommit.model.UpdatePullRequestStatusResult;
import lombok.NonNull;
import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.amazon.awsgurufrontendservice.CodeCommitTestSuite.DESTINATION_BRANCH_NAME;
import static com.amazon.awsgurufrontendservice.IntegrationTestBase.FILES_IN_EACH_COMMIT;
import static com.amazon.awsgurufrontendservice.IntegrationTestBase.MIN_CODE_COMMIT_CREATE_COMMIT_DELAY_MILLIS;
import static com.amazon.awsgurufrontendservice.IntegrationTestBase.isRunningInLambda;

/**
 * Responsible to perform various operations that helps to commit code changes to CodeCommit repositories. This also include
 * creating branches as branches are nothing but a set of commits.
 */
@Log4j2
public class CodeCommitCommiter {

    private final AWSCodeCommit codeCommitClient;
    private final ClassLoader classLoader = getClass().getClassLoader();

    public CodeCommitCommiter(AWSCodeCommit codeCommitClient) {
        this.codeCommitClient = codeCommitClient;
    }


    public String createBranchesAndReturnDestinationCommit(
            @NonNull final String repositoryName,
            @NonNull final String sourcebranchName,
            @NonNull final String destinationbranchName) throws IOException {
        byte[] initialFileContent = Files.readAllBytes(
                Paths.get(getRealPath("tst-resource/repository/InitialCommitFile.java")));
        PutFileEntry initialFile = new PutFileEntry()
                .withFilePath("src/InitialCommitFile.java")
                .withFileContent(ByteBuffer.wrap(initialFileContent));

        String destinationBranchCommitId;
        try {
            GetBranchResult getBranchResult = this.codeCommitClient.getBranch(
                    new GetBranchRequest()
                            .withBranchName(destinationbranchName)
                            .withRepositoryName(repositoryName));
            log.info("Destination commit with branch {} in repository {} is already exist.", destinationbranchName, repositoryName);
            destinationBranchCommitId = getBranchResult.getBranch().getCommitId();
        } catch (BranchDoesNotExistException e) {
            log.info("creating destination commit with branch {} in repository {}", destinationbranchName, repositoryName);
            CreateCommitRequest destinationCommit = new CreateCommitRequest()
                    .withCommitMessage("first commit")
                    .withAuthorName("haha")
                    .withBranchName(destinationbranchName)
                    .withRepositoryName(repositoryName)
                    .withPutFiles(initialFile);

            CreateCommitResult destinationBranchCommit = this.codeCommitClient.createCommit(destinationCommit);
            destinationBranchCommitId = destinationBranchCommit.getCommitId();
        }
        CreateBranchRequest sourceBranch = new CreateBranchRequest()
                .withRepositoryName(repositoryName)
                .withBranchName(sourcebranchName)
                .withCommitId(destinationBranchCommitId);
        this.codeCommitClient.createBranch(sourceBranch);

        return destinationBranchCommitId;
    }

    public CreateCommitResult createAndGetAfterCommit(
            @NonNull final String CodeCommitSourceBranchName,
            @NonNull String destinationBranchCommitId,
            @NonNull final String repositoryName)
            throws IOException, InterruptedException {
        CreateCommitResult afterCommit = null;
        Map<String, PutFileEntry> fileEntryMap = getFileNameToFileEntryMap();

        for (List<String> fileNames : FILES_IN_EACH_COMMIT) {
            List<PutFileEntry> files = fileNames.stream().map(fileEntryMap::get).collect(Collectors.toList());
            CreateCommitRequest sourceCommit = new CreateCommitRequest()
                    .withCommitMessage("first commit")
                    .withAuthorName("guru-dev")
                    .withBranchName(CodeCommitSourceBranchName)
                    .withRepositoryName(repositoryName)
                    .withParentCommitId(destinationBranchCommitId)
                    .withPutFiles(files);
            Thread.sleep(MIN_CODE_COMMIT_CREATE_COMMIT_DELAY_MILLIS);
            afterCommit = this.codeCommitClient.createCommit(sourceCommit);
            destinationBranchCommitId = afterCommit.getCommitId();
        }

        return afterCommit;
    }

    public PullRequest createPullRequest(
            @NonNull final String repositoryName,
            @NonNull final String sourcebranchName,
            @NonNull final String destinationbranchName) {
        String requestId = UUID.randomUUID().toString();
        Target target = new Target()
                .withRepositoryName(repositoryName)
                .withSourceReference(sourcebranchName)
                .withDestinationReference(destinationbranchName);
        CreatePullRequestRequest pullRequestRequest = new CreatePullRequestRequest()
                .withClientRequestToken(requestId)
                .withTitle("Test pull request")
                .withTargets(target);

        return this.codeCommitClient.createPullRequest(pullRequestRequest).getPullRequest();
    }

    public UpdatePullRequestStatusResult closePullRequest(@NonNull final String pullRequestId) {

        log.info("Closing pull request {} ", pullRequestId);
        try {
            return this.codeCommitClient.updatePullRequestStatus(
                    new UpdatePullRequestStatusRequest()
                            .withPullRequestId(pullRequestId)
                            .withPullRequestStatus(PullRequestStatusEnum.CLOSED));
        } catch(Exception e) {
            log.error("failed to close pull request {} with error {}", pullRequestId, e.getMessage());
        }
        return null;
    }

    public DeleteBranchResult deleteBranch(
            @NonNull final String branchName,
            @NonNull final String repositoryName) {
        log.info("Deleting branch {} on repository {}", branchName, repositoryName);
        try {
            if(branchName.equals(DESTINATION_BRANCH_NAME)) {
                throw new DefaultBranchCannotBeDeletedException("Can not delete default branch " + DESTINATION_BRANCH_NAME);
            }
            return this.codeCommitClient.deleteBranch(
                    new DeleteBranchRequest()
                            .withRepositoryName(repositoryName)
                            .withBranchName(branchName));
        } catch(Exception e) {
            log.error("failed to delete branch {} on repository {} with error {}", branchName, repositoryName, e.getMessage());
        }
        return null;
    }

    public Map<String, PutFileEntry> getFileNameToFileEntryMap() throws IOException {
        String repositoryPath = "tst-resource/repository";
        Map<String, PutFileEntry> fileEntryMap = new HashMap<>();
        Files.walk(Paths.get(getRealPath(repositoryPath)))
                .filter(Files::isRegularFile)
                .forEach(file -> {
                    try {
                        byte[] fileContent = Files.readAllBytes(file);
                        ByteBuffer buf = ByteBuffer.wrap(fileContent);
                        fileEntryMap.put(file.getFileName().toString(), new PutFileEntry()
                                .withFilePath(Paths.get(getRealPath(repositoryPath)).relativize(file).toString())
                                .withFileContent(buf));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

        return fileEntryMap;
    }

    protected String getRealPath(final String pathFromRepoRoot) {
        if (isRunningInLambda()) {
            return this.classLoader.getResource(pathFromRepoRoot).getFile();
        } else {
            return pathFromRepoRoot;
        }
    }
}
