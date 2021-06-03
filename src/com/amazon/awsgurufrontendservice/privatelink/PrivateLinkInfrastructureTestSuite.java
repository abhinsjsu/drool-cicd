package com.amazon.awsgurufrontendservice.privatelink;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.retry.PredefinedRetryPolicies;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.CreateVpcEndpointRequest;
import com.amazonaws.services.ec2.model.CreateVpcEndpointResult;
import com.amazonaws.services.ec2.model.DeleteVpcEndpointsRequest;
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsRequest;
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsResult;
import com.amazonaws.services.ec2.model.DescribeSubnetsRequest;
import com.amazonaws.services.ec2.model.DescribeSubnetsResult;
import com.amazonaws.services.ec2.model.DescribeVpcEndpointsRequest;
import com.amazonaws.services.ec2.model.DescribeVpcEndpointsResult;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.TagSpecification;
import com.amazonaws.services.ec2.model.Vpc;
import com.amazonaws.services.ec2.model.VpcEndpoint;
import com.amazonaws.services.ec2.model.VpcEndpointType;
import com.google.common.collect.Lists;
import lombok.extern.log4j.Log4j2;
import org.testng.annotations.Parameters;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Log4j2
/*
  Base class to facilitate the PrivateLink Infrastructure Integration tests
 */
public class PrivateLinkInfrastructureTestSuite {

    private String domain;
    private String region;
    private AmazonEC2 ec2Client;

    @Parameters({"domain", "region"})
    public PrivateLinkInfrastructureTestSuite(final String domain, final String region) {
        this.domain = domain;
        this.region = region;

        this.ec2Client = AmazonEC2ClientBuilder.standard()
                                               .withClientConfiguration(new ClientConfiguration().withRetryPolicy(
                                                       PredefinedRetryPolicies.getDefaultRetryPolicy()))
                                               .withCredentials(new DefaultAWSCredentialsProviderChain())
                                               .withRegion(region)
                                               .build();
    }

    protected String getDefaultVpcId() {
        final Optional<Vpc> vpc = ec2Client.describeVpcs()
                                           .getVpcs()
                                           .stream().filter(Vpc::isDefault).findFirst();
        String vpcId;
        if (vpc.isPresent()) {
            vpcId = vpc.get().getVpcId();
            log.info("Default VpcId: {}", vpcId);
            return vpcId;
        } else {
            throw new RuntimeException("No Default VPC found");
        }
    }

    protected CreateVpcEndpointResult createVpcEndpoint(final String vpcId, final String subnetId,
                                                        final String securityGroupId) {
        CreateVpcEndpointRequest createVpcEndpointRequest = new CreateVpcEndpointRequest().withVpcId(vpcId)
                                                                                          .withSubnetIds(Lists.newArrayList(subnetId))
                                                                                          .withSecurityGroupIds(securityGroupId)
                                                                                          .withServiceName(getVpceServiceName())
                                                                                          .withVpcEndpointType(VpcEndpointType.Interface)
                                                                                          .withPrivateDnsEnabled(true)
                                                                                          .withTagSpecifications(createTag());
        return ec2Client.createVpcEndpoint(createVpcEndpointRequest);
    }

    /**
     * Cleans up the created VPC Endpoint, if exists.
     */
    protected void cleanUpVpcEndpoints() {
        log.info("Cleaning up the VPC Endpoint created by the test");
        final List<String> vpcEndpoints = ec2Client.describeVpcEndpoints()
                                                   .getVpcEndpoints()
                                                   .stream()
                                                   .filter(vpcEndpoint -> vpcEndpoint.getTags().stream()
                                                                                     .anyMatch(tag -> tag.getKey().equals("Name") && tag.getValue().equals("CodeGuru-Reviewer Integ-Test VPC Endpoint")))
                                                   .map(VpcEndpoint::getVpcEndpointId).collect(Collectors.toList());

        if (!vpcEndpoints.isEmpty()) {
            DeleteVpcEndpointsRequest deleteVpcEndpointsRequest = new DeleteVpcEndpointsRequest().withVpcEndpointIds(vpcEndpoints);
            ec2Client.deleteVpcEndpoints(deleteVpcEndpointsRequest);
        }
    }

    protected String getSecurityGroupId(final String vpcId) {
        final DescribeSecurityGroupsRequest describeSecurityGroupsRequest = new DescribeSecurityGroupsRequest()
                                                                                    .withFilters(getVpcFilter(vpcId));
        DescribeSecurityGroupsResult securityGroupsResult = ec2Client.describeSecurityGroups(describeSecurityGroupsRequest);
        if (securityGroupsResult.getSecurityGroups().isEmpty()) {
            throw new RuntimeException("No security group found in VPC");
        }
        return securityGroupsResult.getSecurityGroups().get(0).getGroupId();
    }

    protected DescribeVpcEndpointsResult describeVpceEndpoint(final String vpcEndpointId) {
        final DescribeVpcEndpointsRequest describeVpcEndpointsRequest = new DescribeVpcEndpointsRequest()
                                                                                .withVpcEndpointIds(vpcEndpointId);
        return ec2Client.describeVpcEndpoints(describeVpcEndpointsRequest);
    }

    protected String getSubnetId(final String vpcId) {
        final DescribeSubnetsRequest describeSubnetsRequest = new DescribeSubnetsRequest().withFilters(getVpcFilter(vpcId));
        DescribeSubnetsResult subnetsResult = ec2Client.describeSubnets(describeSubnetsRequest);
        if (subnetsResult.getSubnets().isEmpty()) {
            throw new RuntimeException("No subnet found in VPC");
        }
        return subnetsResult.getSubnets().get(0).getSubnetId();
    }

    protected TagSpecification createTag() {
        final Tag tag = new Tag().withKey("Name")
                                 .withValue("CodeGuru-Reviewer Integ-Test VPC Endpoint");

        return new TagSpecification().withResourceType("vpc-endpoint")
                                     .withTags(tag);
    }

    protected List<Filter> getVpcFilter(final String vpcId) {
        return Lists.newArrayList(new Filter("vpc-id").withValues(vpcId));
    }

    /**
     * Gets the VPCE Service Name as created by the Service owner (viz. AWS CodeGuru-Reviewer => FE Service)
     *
     * @return
     */
    protected String getVpceServiceName() {
        if ("prod".equalsIgnoreCase(domain)) {
            return String.format("com.amazonaws.%s.codeguru-reviewer", region);
        }
        return String.format("com.amazonaws.%s.codeguru-reviewer.%s", region, domain);
    }

}
