import boto3
import botocore

def positive1(self, sqs_arn: str, topic_arn: str) -> None:
    session = botocore.session.get_session()
    sns_client = session.create_client('sns', 'us-west-2')
    # create SNS topic, connect it to the Lambda, publish test message
    sns_client.subscribe(TopicArn=topic_arn, Protocol='sqs',
                         Endpoint=sqs_arn)

def positive2(self, sqs_arn: str, topic_arn: str) -> None:
    session = boto3.Session()
    sns_client = session.client('sns')
    response = sns_client.subscribe(
        TopicArn=topic_arn,
        Protocol='sqs',
        Endpoint=sqs_arn,
        ReturnSubscriptionArn=True)
    return response

def positive3() -> None:
    session = botocore.session.get_session()
    sns_client = session.create_client('sns', 'us-west-2')
    return sns_client.create_topic(Name=TEST_TOPIC_NAME)

def negative1() -> None:
    session = boto3.Session()
    sns_client = session.client('sns')
    topic_info = sns_client.create_topic(Name=TEST_TOPIC_NAME)
    sns_client.subscribe(TopicArn=topic_info['TopicArn'], Protocol='sqs',
                         Endpoint=aws_stack.sqs_queue_arn(TEST_QUEUE_NAME_FOR_SNS))
    test_value = short_uid()
    sns_client.publish(TopicArn=topic_info['TopicArn'], Message='test message for SQS',
                       MessageAttributes={'attr1': {'DataType': 'String', 'StringValue': test_value}})

def negative2() -> None:
    session = botocore.session.get_session()
    sns_client = session.create_client('sns', 'us-west-2')
    # create SNS topic, connect it to the Lambda, publish test message
    num_events_sns = 3
    response = sns_client.create_topic(Name='TEST_TOPIC_NAME')
    sns_client.subscribe(TopicArn=response['TopicArn'], Protocol='lambda',
                         Endpoint=aws_stack.lambda_function_arn('TEST_LAMBDA_NAME_STREAM'))
    for i in range(0, num_events_sns):
        sns_client.publish(TopicArn=response['TopicArn'], Message='test message %s' % i)

def negative3(message, message_attributes, sns_topic_arn=None, sns_topic_name=None):
    sns_client = boto3.client('sns')
    if sns_topic_arn:
        topic_arn = sns_topic_arn
    elif sns_topic_name:
        topic_arn = sns_client.create_topic(Name=sns_topic_name)
    sns_client.publish(TopicArn=topic_arn, Message=message, MessageAttributes=message_attributes)

def negative4(message, message_attributes, sns_topic_arn=None, sns_topic_name=None):
    sns_client = boto3.client('sns')
    if sns_topic_arn:
        topic_arn = sns_topic_arn
    elif sns_topic_name:
        topic_arn = sns_client.create_topic(Name=sns_topic_name)['TopicArn']
    sns_client.publish(TopicArn=topic_arn, Message=message, MessageAttributes=message_attributes)

