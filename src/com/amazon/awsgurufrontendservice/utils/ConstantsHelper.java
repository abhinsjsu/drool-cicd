package com.amazon.awsgurufrontendservice.utils;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ConstantsHelper {

    public final static List<String> EXPECTED_CATEGORIES = Arrays.asList(
            // TODO: enable javabestpractices, codeclone, input validation and info leak
            "aws", "bugfix", "cloudformation", "concurrency", "resourceleak"
    );

    // No need to poll for the first X seconds - we know it won't finish faster than this.
    // Saves us some API calls.
    public static final long MIN_PULL_REQUEST_DELAY_MILLIS = TimeUnit.MINUTES.toMillis(5);
    public static final long MIN_CODEREVIEW_COMPLETED_DELAY_MILLIS = TimeUnit.MINUTES.toMillis(5);
    public static final long MIN_ONBOARDING_DELAY_MILLIS = TimeUnit.MINUTES.toMillis(4);

    // We will do describe for 20 * 30 = 600 seconds or 10 minutes on top of min delays
    // after which we can assume that operation is stuck.
    public static final long POLLING_FREQUENCY_MILLIS = TimeUnit.SECONDS.toMillis(60);
    public static final int MAX_ATTEMPTS = 20;

    public static final String DESTINATION_BRANCH_NAME = "master";
}
