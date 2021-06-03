package com.amazon.samplelib.bugfixtemplate;

/**
 * Expected finding 1.
 *
 * Location: line 15
 * Message: This code uses '%s' to format int: exitValue (declared at line 5). This is a potential
 * locale-sensitive
 * handling issue. It might cause errors in the handling and processing of the statement at line: 6. Consider formatting
 * this data with '%d' instead.
 */
public class BugFix {

    public void printMessage(final int exitValue) {
        final String message = String.format("Process exited with value [%s]", exitValue);
        System.out.println(message);
    }
}
