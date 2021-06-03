package com.amazon.awsgurufrontendservice.utils;

import com.amazonaws.services.secretsmanager.AWSSecretsManager;
import com.amazonaws.services.secretsmanager.AWSSecretsManagerClientBuilder;
import com.amazonaws.services.secretsmanager.model.GetSecretValueRequest;
import com.amazonaws.services.secretsmanager.model.GetSecretValueResult;
import com.google.gson.Gson;
import org.apache.commons.lang.StringUtils;

public class CredentialsHelper {

    private static final Gson gson = new Gson();

    public static <T> T getCredential(
            final String secretId, final String region, Class<T> clazz){
        if (StringUtils.isNotBlank(secretId)) {

            final GetSecretValueResult getSecretValueResult = getSecretValue(secretId, region);
            // Decrypts secret using the associated KMS CMK.
            // Depending on whether the secret is a string or binary, one of these fields will be populated.
            if (getSecretValueResult.getSecretString() != null) {
                return gson.fromJson(getSecretValueResult.getSecretString(), clazz);
            } else {
                throw new RuntimeException("The secretId is null.");
            }
        }  else {
            throw new RuntimeException("A secretId for the credentials must be provided to run tests." +
                    " This should have the username, connectionArn, basicAuthHeader");
        }
    }

    private static GetSecretValueResult getSecretValue (final String secretId, final String region) {
        AWSSecretsManager client = AWSSecretsManagerClientBuilder.standard()
                .withRegion(region)
                .build();
        GetSecretValueRequest getSecretValueRequest = new GetSecretValueRequest()
                .withSecretId(secretId);
        return client.getSecretValue(getSecretValueRequest);
    }
}
