package com.amazon.awsgurufrontendservice.codecommit;

import com.amazon.awsgurufrontendservice.CodeCommitTestSuite;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.STSAssumeRoleSessionCredentialsProvider;
import com.amazonaws.services.gurufrontendservice.AWSGuruFrontendService;
import com.amazonaws.services.gurufrontendservice.model.AWSGuruFrontendServiceException;
import com.amazonaws.services.gurufrontendservice.model.AccessDeniedException;
import com.amazonaws.services.gurufrontendservice.model.DescribeCodeReviewRequest;
import com.amazonaws.services.gurufrontendservice.model.DescribeRepositoryAssociationRequest;
import com.amazonaws.services.gurufrontendservice.model.DescribeRepositoryAssociationResult;
import com.amazonaws.services.gurufrontendservice.model.ListRepositoryAssociationsRequest;
import com.amazonaws.services.gurufrontendservice.model.ListTagsForResourceRequest;
import com.amazonaws.services.gurufrontendservice.model.ListTagsForResourceResult;
import com.amazonaws.services.gurufrontendservice.model.NotFoundException;
import com.amazonaws.services.gurufrontendservice.model.RepositoryAssociationState;
import com.amazonaws.services.gurufrontendservice.model.TagResourceRequest;
import com.amazonaws.services.gurufrontendservice.model.UntagResourceRequest;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagement;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClientBuilder;
import com.amazonaws.services.identitymanagement.model.GetRoleRequest;
import com.amazonaws.services.identitymanagement.model.GetRoleResult;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.GetCallerIdentityRequest;
import com.google.common.collect.ImmutableMap;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpStatus;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Log4j2
public class TagrisTestSuite {
    private static final String CODE_REVIEW_SOURCE_BRANCH_NAME = String.format("CodeReview-%s", System.currentTimeMillis());
    private static final String DESTINATION_BRANCH_NAME = "master";
    private static final long SLEEP_TIME_FOR_PROPAGATION = 7 * 60 * 1000; // 7 minutes since API GW cache for 5 minutes.
    private final CodeCommitTestSuite codeCommitTestSuite;
    private final AWSCredentialsProvider tagrisExclusiveTestCredentials;
    private final CodeCommitCommiter codeCommitCommiter;
    private final String accountId;
    private final AWSGuruFrontendService guruClient;

    @Parameters({"domain", "region"})
    public TagrisTestSuite(final String domain, final String region) {
        codeCommitTestSuite = new CodeCommitTestSuite(domain, region);
        this.guruClient = codeCommitTestSuite.getGuruClient();

        GetRoleRequest getRoleRequest = new GetRoleRequest().withRoleName("TagrisExclusiveTestRole-" + region);
        AmazonIdentityManagement iam =
            AmazonIdentityManagementClientBuilder.defaultClient();
        GetRoleResult getRoleResponse = iam.getRole(getRoleRequest);
        this.tagrisExclusiveTestCredentials =
            new STSAssumeRoleSessionCredentialsProvider
                .Builder(getRoleResponse.getRole().getArn(), "TagrisTest").build();
        this.codeCommitCommiter = new CodeCommitCommiter(codeCommitTestSuite.getCodeCommitClient());

        this.accountId = AWSSecurityTokenServiceClientBuilder.standard().withRegion(region).build()
            .getCallerIdentity(new GetCallerIdentityRequest()).getAccount();
    }

    /**
     *
     * This test verified following things:
     * 1. creates an association with AllowTag and validates the tags are present by calling describe apis. (Tag on create)
     * 2. Create a CodeReview make sure the it's accessible from the policy containing permissi
     *    on to access resource with tag provided in request.
     * 3. Remove the tag and verifies neither onboarding APIs are callable nor codereview APIs.
     * 4. Verified after disassociation, the policy still works for both codereview and repository association.
     * 5. Verifies the list association only returns disassociated repo when disassociated enum is passed.
     * 6. verifies that Describe returns 404 is flag is not passed.
     *
     * @param domain
     * @throws Exception
     */
    @Test(groups = {"onboarding", "tagris-canary", "tagris-test", "canary"})
    @Parameters({"domain"})
    public void testTagrisLifeCycle(final String domain) throws Exception {
        final Map<String, String> onboardingTags = ImmutableMap.of("AllowTag", "Allow");
        final String repositoryName = String.format("Tagris%s%d", domain, System.currentTimeMillis());
        final String codeReviewName =
            String.format("%s-%s-%s", repositoryName, CODE_REVIEW_SOURCE_BRANCH_NAME, System.currentTimeMillis());

        String associationArn = null;
        try {
            codeCommitTestSuite.createRepository(repositoryName);
            associationArn = codeCommitTestSuite.onboard(repositoryName, onboardingTags);
            ListTagsForResourceResult listTagsResult =
                guruClient.listTagsForResource(new ListTagsForResourceRequest()
                    .withResourceArn(associationArn)
                    .withRequestCredentialsProvider(tagrisExclusiveTestCredentials));

            Map<String, String> listTagMap = listTagsResult.getTags();

            Assert.assertTrue(onboardingTags.entrySet().stream()
                .allMatch(entry -> StringUtils.equals(listTagMap.get(entry.getKey()), entry.getValue())));

            final DescribeRepositoryAssociationRequest describeRepositoryAssociationRequest =
                new DescribeRepositoryAssociationRequest()
                    .withAssociationArn(associationArn)
                    .withShowDeletedRepository(true)
                    .withRequestCredentialsProvider(tagrisExclusiveTestCredentials);
            DescribeRepositoryAssociationResult describeRepositoryAssociationResult =
                guruClient.describeRepositoryAssociation(describeRepositoryAssociationRequest);

            Map<String, String> describeRepositoryAssociationResultTags =
                describeRepositoryAssociationResult.getTags();

            Assert.assertTrue(describeRepositoryAssociationResultTags.entrySet().stream()
                .allMatch(entry -> StringUtils.equals(listTagMap.get(entry.getKey()), entry.getValue())));

            String destinationBranchCommitId = this.codeCommitCommiter.createBranchesAndReturnDestinationCommit(
                repositoryName, CODE_REVIEW_SOURCE_BRANCH_NAME, DESTINATION_BRANCH_NAME);
            codeCommitCommiter
                .createAndGetAfterCommit(CODE_REVIEW_SOURCE_BRANCH_NAME, destinationBranchCommitId, repositoryName);
            String codeReviewArn = codeCommitTestSuite
                .validateAndCreateCodeReview(associationArn, codeReviewName, this.CODE_REVIEW_SOURCE_BRANCH_NAME);
            codeCommitTestSuite.waitAndGetCodeReview(codeReviewArn);

            final DescribeCodeReviewRequest describeCodeReviewRequest = new DescribeCodeReviewRequest()
                .withCodeReviewArn(codeReviewArn)
                .withRequestCredentialsProvider(tagrisExclusiveTestCredentials);

            guruClient.describeCodeReview(describeCodeReviewRequest);

            log.info("Removing tags.");
            // Remove tags and then validate one on boarding call one codereview API call.
            guruClient
                .untagResource(new UntagResourceRequest().withResourceArn(associationArn).withTagKeys("AllowTag"));
            Assert.assertThrows(AccessDeniedException.class,
                () -> guruClient.describeRepositoryAssociation(describeRepositoryAssociationRequest));

            Assert.assertThrows(AccessDeniedException.class,
                () -> guruClient.describeCodeReview(describeCodeReviewRequest));

            Thread.sleep(SLEEP_TIME_FOR_PROPAGATION);
            tagrisExclusiveTestCredentials.refresh();

            codeCommitTestSuite.disassociate(associationArn);

            // Should be able to describe repository association without any tags with normal credentials.
            guruClient.describeRepositoryAssociation(new DescribeRepositoryAssociationRequest()
                .withAssociationArn(associationArn).withShowDeletedRepository(true));

            log.info("Validating Auth works as expected after repository is disassociated");

            String finalAssociationArn = associationArn;

            Assert.assertThrows(NotFoundException.class,
                () -> guruClient.describeRepositoryAssociation(
                    new DescribeRepositoryAssociationRequest().withAssociationArn(finalAssociationArn)));

            Assert.assertThrows(AccessDeniedException.class,
                () -> guruClient.listTagsForResource(new ListTagsForResourceRequest().withResourceArn(
                    finalAssociationArn).withRequestCredentialsProvider(tagrisExclusiveTestCredentials)));

            Assert.assertThrows(AccessDeniedException.class,
                () -> guruClient.describeRepositoryAssociation(describeRepositoryAssociationRequest));

            Assert.assertThrows(AccessDeniedException.class,
                () -> guruClient.describeCodeReview(describeCodeReviewRequest));

            log.info("Adding tags back");

            guruClient.tagResource(new TagResourceRequest()
                .withResourceArn(associationArn)
                .withTags(onboardingTags)
                .withRequestCredentialsProvider(tagrisExclusiveTestCredentials));

            Thread.sleep(SLEEP_TIME_FOR_PROPAGATION);
            tagrisExclusiveTestCredentials.refresh();

            guruClient.describeRepositoryAssociation(describeRepositoryAssociationRequest);
            guruClient.describeCodeReview(describeCodeReviewRequest);
        } finally {
            if (associationArn != null) {
                try {
                    codeCommitTestSuite.disassociate(associationArn);
                } catch (AWSGuruFrontendServiceException ex) {
                    log.warn(ex);
                }
            }
            codeCommitTestSuite.deleteRepository(repositoryName);
        }
    }

    @Test(groups = {"tagris-test", "onboarding"})
    public void testMoreThan50Tags() throws InterruptedException {
        Map<String, String> moreThan50Items =
            IntStream.rangeClosed(0, 50).boxed().collect(Collectors.toMap(i -> "Tag" + i.toString(), i -> i.toString()));

        try {
            codeCommitTestSuite.onboard("doesnotmatter", moreThan50Items);
        } catch (AWSGuruFrontendServiceException ex) {
            Assert.assertEquals(ex.getStatusCode(), HttpStatus.SC_BAD_REQUEST);
        }
    }

    @BeforeClass(alwaysRun = true)
    public void cleanUp() {
        codeCommitTestSuite.cleanupOldRepos();
    }
}
