package com.amazon.awsgurufrontendservice.dataprovider;

import com.amazon.awsgurufrontendservice.model.CodeLanguage;
import lombok.extern.log4j.Log4j2;
import org.testng.annotations.DataProvider;

/**
 * Test Data Provider
 */
@Log4j2
public class TestDataProvider {

    @DataProvider
    public static Object[][] dataProviderWithLanguage() {
        return new Object[][] {
            {CodeLanguage.Java},
            {CodeLanguage.Python}
        };
    }

    @DataProvider
    public static Object[][] dataProviderMultiLanguageTest() {
        return new Object[][] {
            {"tst-resource-with-lang/multiLanguage/equal/repository", CodeLanguage.Java},
            {"tst-resource-with-lang/multiLanguage/java/repository", CodeLanguage.Java},
            {"tst-resource-with-lang/multiLanguage/python/repository", CodeLanguage.Python}
        };
    }
}
