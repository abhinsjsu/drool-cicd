import boto3
import botocore

def get_sns_client():
    return boto3.client('sns')

# source: https://code.amazon.com/packages/Uki-proserve-devops-centrica-ukhome-q4-2018/blobs/mainline/--/centrica-ci-pipeline/python_lambdas/lib/dynamodb.py#L20
class Foo():
    def __init__(self) -> None:
        self.client = boto3.client('sns')

    # source: https://code.amazon.com/packages/Moto/blobs/9056d100ac5259c1783866a31341b285fc6edd05/--/tests/test_sns/test_subscriptions_boto3.py#L294
    def positive1(self, sqs_arn: str, topic_arn: str) -> None:
        response = self.client.create_topic(Name='testconfirm')

        self.client.confirm_subscription(
            TopicArn=response['TopicArn'],
            Token='2336412f37fb6fc4a4586147f16916692',
            AuthenticateOnUnsubscribe='true'
        )

# source: https://code.amazon.com/packages/BakerPythonScripts/blobs/mainline/--/src/baker_python_scripts/add_auth_to_sns_sqs_subscription.py#L160
def positive2(self, logging, client_sqs, sqs_url_temp, sns_arn_parts) -> None:
    session = boto3.Session()
    sns_client = session.client('sns')
    sns_client.confirm_subscription(
        TopicArn=sns_arn_parts['arn'], Token=token, AuthenticateOnUnsubscribe='True'
    )

# source: https://code.amazon.com/packages/DAWSCarnavalMonitoring/blobs/mainline/--/bin/gen_rds_monitors.py#L697
def positive3(topicarn: str, topic_arn: str, authenticate) -> None:
    session = botocore.session.get_session()
    sns_client = session.create_client('sns', 'us-west-2')
    try:
        response = sns_client.confirm_subscription(
            TopicArn=topicarn,
            Token=token,
            AuthenticateOnUnsubscribe=authenticate
        )
    except Exception as e:
        logger.warn("Exeption while confirming SNS subscription. "
                    "Error: %s" % e)
        raise e
    return response

# source: https://code.amazon.com/packages/ConsolesHttpsProxyTunnelService/blobs/fc07c42f3747415ab3191d82c3f9dc95f78d972c/--/src/consoles_https_proxy_tunnel_service/sqs_receiver.py#L209
def positive4(self, topic_arn, token):
    # OK, this is a confirmation message, and from the right topic.
    params = {
        "TopicArn": topic_arn,
        "Token": token,
        "AuthenticateOnUnsubscribe": "true",
    }
    sns_session = botocore.session.get_session()
    sns_client = sns_session.create_client("sns")
    sns_client.confirm_subscription(**params)

# source: https://code.amazon.com/packages/BakerPythonScripts/blobs/mainline/--/src/baker_python_scripts/add_auth_to_sns_sqs_subscription.py#L160
def positive5(self, logging, client_sqs, sqs_url_temp, sns_arn_parts) -> None:
    client_sns = get_sns_client()
    logging.info('Obtaining SNS subscription token and confirmimg temporary subscription')
    token = self.iterate_and_execute_over_queue_messages(
        client_sqs, sqs_url_temp, None, self._get_new_sub_token
    )
    client_sns.confirm_subscription(
        TopicArn=sns_arn_parts['arn'], Token=token, AuthenticateOnUnsubscribe='True'
    )