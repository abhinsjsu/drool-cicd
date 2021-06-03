package com.amazon.awsgurufrontendservice;

import com.amazonaws.guru.common.github.GitHubAdminApiFacade;
import lombok.Builder;
import lombok.Getter;

/**
 * Represents a test GitHub repository.
 */
@Builder
@Getter
public class TestGitHubRepository {
    private final String owner;
    private final String name;
    private final String branchName;
    private final GitHubAdminApiFacade gitHubAdminApiFacade;
}
