package com.amazon.samplelib;
/**
 * SampleJavaClass.
 */
public class SampleJavaClass {
    /**
     * Sample method.
     * @return a placeholder string
     */
    public String sampleMethod() {
        final String CodeCommitPullRequestSourceBranchName = String.format("SingleSourceCommitFeature-%s", System.currentTimeMillis());
        
        return "sampleMethod() called!";
    }
}
