package com.amazon.awsgurufrontendservice;

/**
 * Responsible for resolving paths especially for the resource files. The path resolves differently when run on Lambda
 * vs on Tod/DevDesktops.
 */
public class PathResolver {
    private final ClassLoader classLoader = getClass().getClassLoader();

    // Need to use class loader when running on Lambda.
    public String getRealPath(final String pathFromRepoRoot) {
        if (isRunningInLambda()) {
            return classLoader.getResource(pathFromRepoRoot).getFile();
        } else {
            return pathFromRepoRoot;
        }
    }

    // Checking by availability of env variables.
    // https://docs.aws.amazon.com/lambda/latest/dg/lambda-environment-variables.html
    private boolean isRunningInLambda() {
        return System.getenv("AWS_LAMBDA_FUNCTION_NAME") != null;
    }
}
