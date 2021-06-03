package com.amazon.awsgurufrontendservice.github.inference.tests;

import com.amazon.awsgurufrontendservice.GitHubTestSuite;
import com.amazon.awsgurufrontendservice.TestGitHubRepository;
import com.amazon.awsgurufrontendservice.dataprovider.TestDataProvider;
import com.amazon.awsgurufrontendservice.model.CodeLanguage;
import com.amazon.awsgurufrontendservice.pullrequest.GitHubPullRequestRaiser;
import com.amazonaws.guru.common.github.GitHubAdminApiFacade;
import com.amazonaws.guru.common.github.GitHubApiFacade;
import com.amazonaws.guru.common.github.domain.GitHubPullRequest;
import com.amazonaws.services.gurufrontendservice.model.CodeReview;
import com.amazonaws.services.gurufrontendservice.model.ConflictException;
import com.amazonaws.services.gurufrontendservice.model.CreateConnectionTokenResult;
import com.amazonaws.services.gurufrontendservice.model.ProviderType;
import com.amazonaws.services.gurufrontendservice.model.RecommendationSummary;
import com.amazonaws.services.gurufrontendservice.model.ResourceNotFoundException;
import com.amazonaws.services.gurufrontendservice.model.Type;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.GetCallerIdentityRequest;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.log4j.Log4j2;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Log4j2
@Test(groups = {"inference-integ", "gh-inference"})
public class RecommendationsInferenceGitHubTestSuite  extends GitHubTestSuite {

    private String accountId;
    private String repositoryName;
    private String repositoryNameCMCMK;
    private String region;
    private Map<CodeLanguage, CodeReviewInfo> languageCodeReviewInfoMap;
    private Map<CodeLanguage, CodeReviewInfo> languageCodeReviewInfoMapCMCMK;
    private final ProviderType providerType = ProviderType.GitHub;
    private final static String DESTINATION_BRANCH_NAME = "main";
    private static final String RECOMMENDATION_SOURCE_BRANCH_NAME =
        String.format("RecommendationFeature-%s", System.currentTimeMillis());
    private String associationArn;
    private String associationArnCMCMK;

    @Getter
    static private class CodeReviewInfo {
        private String repositoryName;
        private String codeReviewArn;

        public CodeReviewInfo(final String repositoryName, final String codeReviewArn) {
            this.repositoryName = repositoryName;
            this.codeReviewArn = codeReviewArn;
        }
    }

    @Parameters({"domain", "region", "secretId", "secretIdForkedRepo"})
    public RecommendationsInferenceGitHubTestSuite(@NonNull final String domain,
                                                   @NonNull final String region,
                                                   @Optional final String secretId,
                                                   @Optional final String secretIdForkedRepo) {
        super(domain, region, secretId, secretIdForkedRepo);
        languageCodeReviewInfoMap = new HashMap<>();
        languageCodeReviewInfoMapCMCMK = new HashMap<>();
        associationArn = null;
        associationArnCMCMK = null;
    }

    @Parameters({"domain", "region"})
    @BeforeClass(groups = {"canary", "github-pullrequest-inference-canary"})
    public void beforeClass(@NonNull final String domain,
                            @NonNull final String region) throws Exception {

        // Commenting the cleanup to retain the repository retained by 404
        //cleanupOldRepos();
        refreshGuruClient();
        // Using this flag to skip deletion of repository when 404 occurs
        this.accountId = AWSSecurityTokenServiceClientBuilder.standard().withRegion(region).build()
                .getCallerIdentity(new GetCallerIdentityRequest()).getAccount();
        this.repositoryName = String.format("%s-%s-%s-%s", DELETE_INFERENCE_REPOSITORY_NAME_PREFIX,
                GITHUB_RECOMMENDATIONFEEDBACK_REPOSITORY_NAME_PREFIX, domain, region);
        this.repositoryNameCMCMK = String.format("CMCMK-%s-%s-%s-%s", DELETE_INFERENCE_REPOSITORY_NAME_PREFIX,
                GITHUB_RECOMMENDATIONFEEDBACK_REPOSITORY_NAME_PREFIX, domain, region);
        this.region = region;
        final Object[][] languages = TestDataProvider.dataProviderWithLanguage();
        for (Object[] languageData : languages) {
            final CodeLanguage language = (CodeLanguage) languageData[0];
            final String repoName = String.format("%s-%s", this.repositoryName,
                language.toLowerCaseString());
            final String crArn = setupTest(repoName, language);
            languageCodeReviewInfoMap.put(language, new CodeReviewInfo(repoName, crArn));

            final String repoNameCMCMK = String.format("%s-%s", this.repositoryNameCMCMK,
                    language.toLowerCaseString());
            final String crArnCMCMK = setupTestCMCMK(repoNameCMCMK, language);
            languageCodeReviewInfoMapCMCMK.put(language, new CodeReviewInfo(repoNameCMCMK, crArnCMCMK));
        }
    }

    @AfterClass(groups = {"canary", "github-pullrequest-inference-canary"})
    public void afterClass() throws Exception{
        if (associationArn != null) {
            disassociate(associationArn);
        }
        deleteRepository(repositoryName);

        // CMCMK
        if (associationArnCMCMK != null) {
            disassociate(associationArnCMCMK);
        }
        deleteRepository(repositoryNameCMCMK);
    }

    /**
     * SetUp the pull request given the repo name and code language,
     * close open resources if an exception is encountered
     * @param repositoryName
     * @param language
     * @return
     * @throws Exception
     */
    private String setupTest(final String repositoryName, final CodeLanguage language) throws Exception {
        log.info("Setting up Github codereview for repository {} ", repositoryName);
        try {
            createRepository(repositoryName);
        } catch (Exception e) {
            if(!e.toString().contains(GITHUB_REPOSITORY_ALREADY_CREATED)) {
                throw e;
            }
            log.info("Repository {} is already created.", repositoryName);
        }

        final CreateConnectionTokenResult connectionToken = fetchConnectionToken();
        try {
            associationArn = onboard(repositoryName, getOwner(), connectionToken.getConnectionToken());
        } catch (ConflictException exception){
            log.info("Repository {} is already associated.", repositoryName);
        }

        String codeReviewArn;
        try {
            // setup github pull request raiser
            GitHubPullRequestRaiser gitHubPullRequestRaiser =
                setupGitHubPullRequestRaiser(RECOMMENDATION_SOURCE_BRANCH_NAME, repositoryName,
                getOwner(), getGitHubApiFacade(), getGitHubAdminApiFacade(), language);
            GitHubPullRequest gitHubPullRequest = gitHubPullRequestRaiser.raisePullRequest();
            CodeReview codeReview =  waitAndGetCodeReview(this.accountId, Type.PullRequest, gitHubPullRequest.getHead().getSha(),
                this.providerType, Integer.toString(gitHubPullRequest.getNumber()), this.region, repositoryName);
            codeReviewArn = codeReview.getCodeReviewArn();

            List<RecommendationSummary> recommendationSummaryList =
                waitForCodeGuruReviewerRecommendations(repositoryName, codeReviewArn, false);
            verifyRecommendations(recommendationSummaryList, repositoryName, EXPECTED_CATEGORIES_MAP.get(language));
        } catch (final ResourceNotFoundException ex) { // Handling this to debug webhook issues.
            log.info("Repository {} is not deleted for further analysis.", repositoryName);
            throw ex;
        }
        return codeReviewArn;
    }

//    @Test(groups = {"canary", "github-pullrequest-inference-canary"}, dataProvider = "dataProviderWithLanguage",
//        dataProviderClass = TestDataProvider.class)
//    public void codeReviewListPaginateGithubTest(final CodeLanguage language) throws Exception{
//        final String repositoryName = languageCodeReviewInfoMap.get(language).getRepositoryName();
//        codeReviewListPaginateTest(repositoryName, this.providerType);
//    }

    private String setupTestCMCMK(final String repositoryName, final CodeLanguage language) throws Exception {
        log.info("Setting up Github codereview for repository {} ", repositoryName);
        try {
            createRepository(repositoryName);
        } catch (Exception e) {
            if(!e.toString().contains(GITHUB_REPOSITORY_ALREADY_CREATED)) {
                throw e;
            }
            log.info("Repository {} is already created.", repositoryName);
        }

        final CreateConnectionTokenResult connectionToken = fetchConnectionToken();
        try {
            associationArnCMCMK = onboard(repositoryName, getOwner(), connectionToken.getConnectionToken(), java.util.Optional.of(createKMSKeyDetailsCmCmk()));
        } catch (ConflictException exception){
            log.info("Repository {} is already associated.", repositoryName);
        }

        String codeReviewArn;
        try {
            // setup github pull request raiser
            GitHubPullRequestRaiser gitHubPullRequestRaiser =
                    setupGitHubPullRequestRaiser(RECOMMENDATION_SOURCE_BRANCH_NAME, repositoryName,
                            getOwner(), getGitHubApiFacade(), getGitHubAdminApiFacade(), language);
            GitHubPullRequest gitHubPullRequest = gitHubPullRequestRaiser.raisePullRequest();
            CodeReview codeReview =  waitAndGetCodeReview(this.accountId, Type.PullRequest, gitHubPullRequest.getHead().getSha(),
                    this.providerType, Integer.toString(gitHubPullRequest.getNumber()), this.region, repositoryName);
            codeReviewArn = codeReview.getCodeReviewArn();

            List<RecommendationSummary> recommendationSummaryList =
                    waitForCodeGuruReviewerRecommendations(repositoryName, codeReviewArn, false);
            verifyRecommendations(recommendationSummaryList, repositoryName, EXPECTED_CATEGORIES_MAP.get(language));
        } catch (final ResourceNotFoundException ex) { // Handling this to debug webhook issues.
            log.info("Repository {} is not deleted for further analysis.", repositoryName);
            throw ex;
        }
        return codeReviewArn;
    }

    @Test(groups = {"canary", "github-pullrequest-inference-canary"}, dataProvider = "dataProviderWithLanguage",
        dataProviderClass = TestDataProvider.class)
    public void codeReviewListPaginateGithubTest(final CodeLanguage language) throws Exception{
        final String repositoryName = languageCodeReviewInfoMap.get(language).getRepositoryName();
        codeReviewListPaginateTest(repositoryName, this.providerType);
    }

    @Test(groups = {"canary", "github-pullrequest-inference-canary"}, dataProvider = "dataProviderWithLanguage",
        dataProviderClass = TestDataProvider.class)
    public void codeReviewGithubTest(final CodeLanguage language) throws Exception {
        final CodeReviewInfo crInfo = languageCodeReviewInfoMap.get(language);
        final String repositoryName = crInfo.getRepositoryName();
        final String codeReviewArn = crInfo.getCodeReviewArn();
        if (codeReviewArn.length() < 1) {
            Assert.fail(String.format("Could not find code review Arn for %s",
                repositoryName));
        }

        codeReviewTest(repositoryName, codeReviewArn, this.providerType, language);
    }

    @Test(groups = {"canary", "github-pullrequest-inference-canary"}, dataProvider = "dataProviderWithLanguage",
            dataProviderClass = TestDataProvider.class)
    public void codeReviewGithubTestCMCMK(final CodeLanguage language) throws Exception {
        final CodeReviewInfo crInfo = languageCodeReviewInfoMapCMCMK.get(language);
        final String repositoryName = crInfo.getRepositoryName();
        final String codeReviewArn = crInfo.getCodeReviewArn();
        if (codeReviewArn.length() < 1) {
            Assert.fail(String.format("Could not find code review Arn for %s",
                    repositoryName));
        }
        codeReviewTest(repositoryName, codeReviewArn, this.providerType, language);
    }

    protected GitHubPullRequestRaiser setupGitHubPullRequestRaiser(
            @NonNull final String recommendationSourceBranchName,
            @NonNull final String repositoryName,
            final String owner,
            final GitHubApiFacade gitHubApiFacade,
            final GitHubAdminApiFacade gitHubAdminApiFacade,
            final CodeLanguage language) throws Exception {

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

        setupSourceBranchForPullRequest(DESTINATION_BRANCH_NAME, sourceRepository,
            String.format("tst-resource-with-lang/%s/repository", language.toLowerCaseString())
        );
        return new GitHubPullRequestRaiser(
                destinationRepository,
                sourceRepository,
                gitHubApiFacade);
    }
}
