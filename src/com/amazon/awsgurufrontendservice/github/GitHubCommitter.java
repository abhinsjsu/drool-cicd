package com.amazon.awsgurufrontendservice.github;

import com.amazon.awsgurufrontendservice.PathResolver;
import com.amazon.awsgurufrontendservice.TestGitHubRepository;
import com.amazon.coral.retry.RetryStrategy;
import com.amazon.coral.retry.RetryingCallable;
import com.amazon.coral.retry.strategy.ExponentialBackoffAndJitterBuilder;
import com.amazonaws.guru.common.exceptions.GitHubApiRetryableException;
import com.amazonaws.guru.common.github.domain.GitHubCommitRequest;
import com.amazonaws.guru.common.github.domain.GitHubCommitResponse;
import com.amazonaws.guru.common.github.domain.GitHubRef;
import com.amazonaws.guru.common.github.domain.GitHubTree;
import com.amazonaws.guru.common.github.domain.GitHubTreeRequest;
import com.amazonaws.guru.common.github.domain.GitHubTreeResponse;
import com.google.common.base.Charsets;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Responsible to perform various operations that helps to commit code changes to GitHub repositories. This also include
 * creating branches as branches are nothing but a set of commits.
 */
@Log4j2
public class GitHubCommitter {
    private static final int WAIT_TIME = 2000;
    private static final float BACK_OFF_FACTOR = 0.5f;
    private static final int GITHUB_API_RETRIES = 5;

    private final PathResolver pathResolver = new PathResolver();

    protected static final RetryStrategy<Void> RETRY_STRATEGY = new ExponentialBackoffAndJitterBuilder()
        .retryOn(GitHubApiRetryableException.class)
        .withMaxElapsedTimeMillis(WAIT_TIME)
        .withMaxAttempts(GITHUB_API_RETRIES)
        .withExponentialFactor(BACK_OFF_FACTOR)
        .newStrategy();

    private final TestGitHubRepository repository;

    public GitHubCommitter(final TestGitHubRepository repository) {
        this.repository = repository;
    }

    public GitHubRef getBranchRef(final String branchName) throws Exception {
        return (GitHubRef) new RetryingCallable(RETRY_STRATEGY,
            () -> repository.getGitHubAdminApiFacade().getGitHubRef(repository.getOwner(),
                repository.getName(), "heads/" + branchName)).call();
    }

    public GitHubRef createBranch(final String branchName, final String fromBranch) throws Exception {
        GitHubRef fromBranchRef = getBranchRef(fromBranch);
        GitHubRef sourceFeatureBranchRef = GitHubRef.builder()
            .ref("refs/heads/" + branchName)
            .sha(fromBranchRef.getObject().getSha())
            .build();
        return
            (GitHubRef) new RetryingCallable(RETRY_STRATEGY, () -> repository.getGitHubAdminApiFacade()
                .createGitHubRef(repository.getOwner(), repository.getName(), sourceFeatureBranchRef)).call();
    }

    public GitHubRef commit(final String branchName, final String commitFilesPath) throws Exception {
        GitHubRef branchRef = getBranchRef(branchName);
        return commit(branchRef, commitFilesPath);
    }

    public GitHubRef commit(final GitHubRef branch, final String commitFilesPath) throws Exception {
        //create the Tree of the changes to commit
        Map<String, GitHubTree> initialTree = getFilesToCommit(commitFilesPath);
        GitHubTreeRequest treeRequest = GitHubTreeRequest.builder()
            .baseTree(branch.getObject().getSha())
            .tree(new ArrayList<>(initialTree.values()))
            .build();
        final GitHubTreeResponse gitHubTreeResponse = (GitHubTreeResponse) new RetryingCallable(RETRY_STRATEGY, () ->
            repository.getGitHubAdminApiFacade().createTree(repository.getOwner(), repository.getName(), treeRequest)).call();
        log.info("GitHub Tree response " + gitHubTreeResponse.getSha());

        // Push commit using the tree ref to commit branch
        GitHubCommitRequest request = GitHubCommitRequest.builder()
            .message("commit for recommendation")
            .parents(Arrays.asList(branch.getObject().getSha()))
            .tree(gitHubTreeResponse.getSha())
            .build();
        final GitHubCommitResponse commitResponse =
            (GitHubCommitResponse) new RetryingCallable(RETRY_STRATEGY, () ->
                repository.getGitHubAdminApiFacade().createCommit(repository.getOwner(), repository.getName(), request)).call();

        log.info("Latest commit " + commitResponse.getSha());

        //update the ref to head of the destination branch - this will put the new commit as head
        final GitHubRef refUpdate = GitHubRef.builder()
            .sha(commitResponse.getSha())
            .force(true)
            .build();

        // getRef() returns refs/heads/<branch-name> but updateGitHubRef API requires heads/<branch-name>
        String branchRef = StringUtils.substringAfter(branch.getRef(), "refs/");
        log.info("Branch ref: " + branchRef);
        return (GitHubRef) new RetryingCallable(RETRY_STRATEGY, () -> repository.getGitHubAdminApiFacade()
            .updateGitHubRef(repository.getOwner(), repository.getName(), branchRef, refUpdate)).call();

    }

    private Map<String, GitHubTree> getFilesToCommit(final String commitFilesPath) throws IOException {
        Map<String, GitHubTree> gitHubTreeMap = new HashMap<>();

        Files.walk(Paths.get(pathResolver.getRealPath(commitFilesPath)))
            .filter(Files::isRegularFile)
            .forEach(file -> {
                try {
                    byte[] fileContent = Files.readAllBytes(file);
                    gitHubTreeMap.put(file.getFileName().toString(),
                        GitHubTree.builder()
                            .path(Paths.get(pathResolver.getRealPath(commitFilesPath)).relativize(file).toString())
                            .mode("100644")
                            .type("blob")
                            .content(new String(fileContent, Charsets.UTF_8))
                            .build());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

        return gitHubTreeMap;
    }

}
