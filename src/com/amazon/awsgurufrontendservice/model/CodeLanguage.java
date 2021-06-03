package com.amazon.awsgurufrontendservice.model;

/**
 * Model for the source code language
 */
public enum CodeLanguage {
    Java, Python;

    public String toLowerCaseString() {
        return toString().toLowerCase();
    }
}
