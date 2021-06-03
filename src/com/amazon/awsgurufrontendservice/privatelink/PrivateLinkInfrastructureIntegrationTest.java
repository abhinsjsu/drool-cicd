package com.amazon.awsgurufrontendservice.privatelink;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.CreateVpcEndpointResult;
import com.amazonaws.services.ec2.model.DescribeVpcEndpointsResult;
import lombok.extern.log4j.Log4j2;
import org.testng.Assert;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

@Log4j2
@Test(groups = {"private-link"})
public class PrivateLinkInfrastructureIntegrationTest extends PrivateLinkInfrastructureTestSuite {

    private AmazonEC2 ec2Client;
    private final int MAX_POLL_ATTEMPT = 20;

    @Parameters({"domain", "region"})
    public PrivateLinkInfrastructureIntegrationTest(final String domain, final String region) {
        super(domain, region);
    }

    @Parameters({"domain", "region"})
    @Test
    /*
      This test simulates as if a customer is creating an VPC Endpoint in their VPC against CodeGuru-Reviewer VPCE Service
     */
    public void createConsumerVpcEndpoint() throws Exception {
        final String vpcId = getDefaultVpcId();

        final String subnetId = getSubnetId(vpcId);
        final String securityGroupId = getSecurityGroupId(vpcId);

        try {

            log.info("Starting to create a VPC Endpoint in VPC: [{}]", vpcId);
            final CreateVpcEndpointResult vpcEndpointResult = createVpcEndpoint(vpcId, subnetId, securityGroupId);
            final String vpcEndpointId = vpcEndpointResult.getVpcEndpoint().getVpcEndpointId();

            log.info("VPC Endpoint: [{}] created", vpcEndpointId);

            int counter = 0;
            DescribeVpcEndpointsResult describeVpcEndpointsResult;
            do {
                describeVpcEndpointsResult = describeVpceEndpoint(vpcEndpointId);
                log.info("Polling the VPC Endpoint: [{}] to become available, with current state: [{}]...", vpcEndpointId,
                        describeVpcEndpointsResult.getVpcEndpoints().get(0).getState());
                // Adding a minimal sleep here to avoid burst of calls, since usually the provisioning takes about ~1-2 mins
                Thread.sleep(Duration.of(15, ChronoUnit.SECONDS).toMillis());
                counter++;
            } while (!("available").equals(describeVpcEndpointsResult.getVpcEndpoints().get(0).getState()) &&
                             counter < MAX_POLL_ATTEMPT);

            Assert.assertEquals("available", describeVpcEndpointsResult.getVpcEndpoints().get(0).getState());
            Assert.assertEquals(getVpceServiceName(), describeVpcEndpointsResult.getVpcEndpoints().get(0).getServiceName());

        } catch (final Exception e) {
            String errMsg = String.format("Failed to create VPC Endpoint for VPCE Service: %s", getVpceServiceName());
            log.error(errMsg, e);
            throw new RuntimeException(errMsg, e);
        } finally {
            cleanUpVpcEndpoints();
        }
    }


}