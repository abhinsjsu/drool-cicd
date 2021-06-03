package com.amazon.awsgurufrontendservice.github.inference.tests;

import com.amazon.awsgurufrontendservice.GitHubTestSuite;
import com.amazon.awsgurufrontendservice.TestGitHubRepository;
import com.amazon.awsgurufrontendservice.dataprovider.TestDataProvider;
import com.amazon.awsgurufrontendservice.model.CodeLanguage;
import com.amazon.awsgurufrontendservice.pullrequest.GitHubPullRequestRaiser;
import com.amazonaws.guru.common.github.GitHubAdminApiFacade;
import com.amazonaws.guru.common.github.GitHubApiFacade;
import com.amazonaws.services.gurufrontendservice.model.ConflictException;
import com.amazonaws.services.gurufrontendservice.model.CreateConnectionTokenResult;
import com.amazonaws.services.gurufrontendservice.model.ProviderType;
import com.amazonaws.services.gurufrontendservice.model.ResourceNotFoundException;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.GetCallerIdentityRequest;
import lombok.NonNull;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.RandomStringUtils;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Log4j2
@Test(groups = {"inference-integ"})
public class CreateCodeReviewGitHubTestSuite extends GitHubTestSuite {

    private final String accountId;
    private final String repositoryName;
    private final String repositoryNameCMCMK;
    private final ProviderType providerType = ProviderType.GitHub;
    private final static String DESTINATION_BRANCH_NAME = "main";
    private static final String CODE_REVIEW_SOURCE_BRANCH_NAME = String.format("CodeReview-%s", System.currentTimeMillis());
    private static final int RANDOM_STRING_LENGTH = 5;

    private String associationArn = null;
    // Using this flag to skip deletion and disassociation of repository when 404 occurs
    private boolean keepRepositoryForDebugging;
    private boolean testSetupCompleted = false;

    private String associationArnCMCMK = null;
    // Using this flag to skip deletion and disassociation of CMCMK repository when 404 occurs
    private boolean keepRepositoryForDebuggingCMCMK;
    private boolean testSetupCompletedCMCMK = false;

    @Parameters({"domain", "region", "secretId", "secretIdForkedRepo"})
    public CreateCodeReviewGitHubTestSuite(final String domain, final String region,
                                           @Optional final String secretId,
                                           @Optional final String secretIdForkedRepo) {
        super(domain, region, secretId, secretIdForkedRepo);
        this.accountId = AWSSecurityTokenServiceClientBuilder.standard().withRegion(region).build()
                .getCallerIdentity(new GetCallerIdentityRequest()).getAccount();
        this.repositoryName = String.format("%s-%s-%s-%s", DELETE_INFERENCE_REPOSITORY_NAME_PREFIX,
                GITHUB_CREATE_CODEREVIEW_REPOSITORY_NAME_PREFIX, domain, region);
        this.repositoryNameCMCMK = String.format("CMCMK-%s-%s-%s-%s", DELETE_INFERENCE_REPOSITORY_NAME_PREFIX,
                GITHUB_CREATE_CODEREVIEW_REPOSITORY_NAME_PREFIX, domain, region);

    }

    @BeforeTest(groups = {"github-repositoryanalysis-inference-canary", "inference-integ", "canary"})
    public void setupTestResources() throws Exception {
        log.info("Setting up Github Code Review test resources");
        keepRepositoryForDebugging = false;

        // Commenting the cleanup to retain the repository retained by 404
        //cleanupOldRepos();
        refreshGuruClient();

        // Create Repository
        log.info("Creating repository {} for Github Pull Request Review Test.", repositoryName);
        try {
            createRepository(repositoryName);
        } catch (Exception e) {
            if(!e.toString().contains(this.GITHUB_REPOSITORY_ALREADY_CREATED)) {
                throw e;
            }
            log.info("Repository {} is already created.", repositoryName);
        }

        // Onboard Repository
        final CreateConnectionTokenResult connectionToken = fetchConnectionToken();
        try {
            associationArn = onboard(repositoryName, getOwner(), connectionToken.getConnectionToken());
        } catch (final ConflictException exception) {
            log.info("Repository {} is already associated.", repositoryName);
        }
        testSetupCompleted = true;

        // CMCMK setup
        refreshGuruClient();

        // Create Repository
        log.info("Creating repository {} for Github Pull Request Review Test.", repositoryName);
        try {
            createRepository(repositoryNameCMCMK);
        } catch (Exception e) {
            if(!e.toString().contains(this.GITHUB_REPOSITORY_ALREADY_CREATED)) {
                throw e;
            }
            log.info("Repository {} is already created.", repositoryNameCMCMK);
        }

        // Onboard Repository
        final CreateConnectionTokenResult connectionTokenCMCMK = fetchConnectionToken();
        try {
            associationArnCMCMK = onboard(repositoryNameCMCMK, getOwner(), connectionTokenCMCMK.getConnectionToken(), java.util.Optional.of(createKMSKeyDetailsCmCmk()));
        } catch (final ConflictException exception) {
            log.info("Repository {} is already associated.", repositoryNameCMCMK);
        }
        testSetupCompletedCMCMK = true;
    }

    @AfterTest(groups = {"github-repositoryanalysis-inference-canary", "inference-integ", "canary"})
    void cleanupTestResources() throws Exception {
        log.info("Cleaning up Github Code Review test resources");
        if (!keepRepositoryForDebugging) {
            if (associationArn != null) {
                log.info("Disassociating repository {} with association ARN {} after test",
                    repositoryName, associationArn);
                disassociate(associationArn);
            }
            log.info("Deleting repository {}", repositoryName);
            deleteRepository(repositoryName);
        } else {
            log.info("Repository {} is kept for further analysis", repositoryName);
        }

        log.info("Cleaning up Github Code Review test resources for CMCMK");
        if (!keepRepositoryForDebuggingCMCMK) {
            if (associationArnCMCMK != null) {
                log.info("Disassociating repository {} with association ARN {} after test",
                        repositoryNameCMCMK, associationArnCMCMK);
                disassociate(associationArnCMCMK);
            }
            log.info("Deleting repository {}", repositoryNameCMCMK);
            deleteRepository(repositoryNameCMCMK);
        } else {
            log.info("Repository {} is kept for further analysis", repositoryNameCMCMK);
        }
    }

    @Test(groups = {"canary", "github-repositoryanalysis-inference-canary"},
        dataProvider = "dataProviderWithLanguage", dataProviderClass = TestDataProvider.class)
    public void createCodeReviewGithubTest(@NonNull final CodeLanguage language) throws Exception {
        if (testSetupCompleted) {
            log.info("Resources setup successfully for Github repository {}", repositoryName);
        } else {
            throw new SkipException("Skipping Github Code Review test as resources failed to setup");
        }

        final String codeReviewName = checkAndTrimCodeReviewName(
            String.format("%s-%s", this.repositoryName, language.toLowerCaseString()));
        final String codeReviewBranchName = String.format("%s-SingleLang-%s",
            CODE_REVIEW_SOURCE_BRANCH_NAME, language.toLowerCaseString());

        log.info("Starting create code review test for Github repository {} ", repositoryName);
        try {
            createCodeReviewAndVerify(this.repositoryName, this.associationArn, codeReviewName, codeReviewBranchName, language);
        } catch (final ResourceNotFoundException ex) { // Handling this to debug webhook issues.
            log.info("Repository {} encountered error on single language branch {}, kept for additional debugging.",
                this.repositoryName, codeReviewBranchName);
            keepRepositoryForDebugging = true;
            throw ex;
        }
    }

    @Test(groups = {"canary", "github-repositoryanalysis-inference-canary"},
            dataProvider = "dataProviderWithLanguage", dataProviderClass = TestDataProvider.class)
    public void createCodeReviewGithubTestCMCMK(@NonNull final CodeLanguage language) throws Exception {
        if (testSetupCompletedCMCMK) {
            log.info("Resources setup successfully for Github CMCMK repository {}", this.repositoryNameCMCMK);
        } else {
            throw new SkipException("Skipping Github Code Review test as resources failed to setup");
        }

        final String codeReviewName = checkAndTrimCodeReviewName(
                String.format("%s-%s", this.repositoryNameCMCMK, language.toLowerCaseString()));
        final String codeReviewBranchName = String.format("%s-SingleLang-%s",
                CODE_REVIEW_SOURCE_BRANCH_NAME, language.toLowerCaseString());

        log.info("Starting create code review test for Github repository {} ", this.repositoryNameCMCMK);
        try {
            createCodeReviewAndVerify(this.repositoryNameCMCMK, this.associationArnCMCMK, codeReviewName, codeReviewBranchName, language);
        } catch (final ResourceNotFoundException ex) { // Handling this to debug webhook issues.
            log.info("Repository {} encountered error on single language branch {}, kept for additional debugging.",
                    this.repositoryNameCMCMK, codeReviewBranchName);
            keepRepositoryForDebuggingCMCMK = true;
            throw ex;
        }
    }

    @Test(groups = {"canary", "github-repositoryanalysis-inference-canary"},
        dataProvider = "dataProviderMultiLanguageTest", dataProviderClass = TestDataProvider.class)
    public void multiLanguageCodeReviewGithubTest(final String repoPath, final CodeLanguage expectedLanguage)
        throws Exception{
        if (testSetupCompleted) {
            log.info("Resources setup successfully for Github repository {}", repositoryName);
        } else {
            throw new SkipException("Skipping Github Code Review test as resources failed to setup");
        }

        final String nameSuffix = String.format("%s-%s", expectedLanguage.toLowerCaseString(),
            getRandomString(RANDOM_STRING_LENGTH));
        final String codeReviewName = checkAndTrimCodeReviewName(
            String.format("%s-%s", this.repositoryName, nameSuffix));
        final String codeReviewBranchName = String.format("%s-MultiLang-%s", CODE_REVIEW_SOURCE_BRANCH_NAME, nameSuffix);

        log.info("Starting MultiLanguage create code review test for Github repository {} ", repositoryName);
        try {
            Assert.assertNotNull(String.format("Repository %s is not associated", repositoryName), associationArn);
            // setup github pull request raiser
            setupGitHubPullRequestRaiser(codeReviewBranchName,
                repositoryName, getOwner(),
                getGitHubApiFacade(), getGitHubAdminApiFacade(), repoPath);
            String codeReviewArn = validateAndCreateCodeReview(associationArn, codeReviewName,
                codeReviewBranchName);
            waitAndGetCodeReview(codeReviewArn);
            waitForCodeGuruReviewerRecommendations(repositoryName, codeReviewArn, false);

            codeReviewTestWithExpectedLanguage(repositoryName, codeReviewArn, this.providerType, expectedLanguage);

        } catch (final ResourceNotFoundException ex) { // Handling this to debug webhook issues.
            log.info("Repository {} encountered error on multi language branch {}, kept for additional debugging.",
                repositoryName, codeReviewBranchName);
            keepRepositoryForDebugging = true;
            throw ex;
        }
    }

    private void createCodeReviewAndVerify(String repositoryName, String associationArn, String codeReviewName, String codeReviewBranchName, CodeLanguage language) throws Exception {
        Assert.assertNotNull(String.format("Repository %s is not associated", repositoryName), associationArn);
        // setup github pull request raiser
        setupGitHubPullRequestRaiser(codeReviewBranchName, repositoryName, getOwner(),
                getGitHubApiFacade(), getGitHubAdminApiFacade(),
                String.format("tst-resource-with-lang/%s/repository", language.toLowerCaseString()));
        String codeReviewArn = validateAndCreateCodeReview(associationArn, codeReviewName,
                codeReviewBranchName);
        waitAndGetCodeReview(codeReviewArn);
        waitForCodeGuruReviewerRecommendations(repositoryName, codeReviewArn, false);

        codeReviewTest(repositoryName, codeReviewArn, this.providerType, language);
    }

    private String getRandomString(final int len) {
        final boolean useLetters = true;
        final boolean useNumbers = false;
        return RandomStringUtils.random(len, useLetters, useNumbers);
    }

    protected GitHubPullRequestRaiser setupGitHubPullRequestRaiser(
            @NonNull final String recommendationSourceBranchName,
            @NonNull final String repositoryName,
            final String owner,
            final GitHubApiFacade gitHubApiFacade,
            final GitHubAdminApiFacade gitHubAdminApiFacade,
            final String repoPath) throws Exception {

        TestGitHubRepository destinationRepository = TestGitHubRepository.builder()
                .name(repositoryName)
                .owner(owner)
                .branchName(DESTINATION_BRANCH_NAME)
                .gitHubAdminApiFacade(gitHubAdminApiFacade)
                .build();

        TestGitHubRepository sourceRepository = TestGitHubRepository.builder()
                .name(repositoryName)
                .owner(owner)
                .branchName(recommendationSourceBranchName)
                .gitHubAdminApiFacade(gitHubAdminApiFacade)
                .build();

        setupSourceBranchForPullRequest(DESTINATION_BRANCH_NAME, sourceRepository, repoPath);
        return new GitHubPullRequestRaiser(
                destinationRepository,
                sourceRepository,
                gitHubApiFacade);
    }

}
