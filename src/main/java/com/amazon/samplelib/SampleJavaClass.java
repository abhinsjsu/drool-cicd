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
        final String CodeCommitPullRequestSourceBranchName = String.format("SingleSourceCommitFeature-%d", System.currentTimeMillis());
        
        return "sampleMethod() called!";
    }
    
      public String sampleMethod1() {
        final String CodeCommitPullRequestSourceBranchName = String.format("SingleSourceCommitFeature-%s", System.currentTimeMillis());
        
        return "sampleMethod() called!";
    }
}
