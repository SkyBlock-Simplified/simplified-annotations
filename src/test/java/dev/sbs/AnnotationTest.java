package dev.sbs;

import dev.sbs.annotation.ResourcePath;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.Test;

public class AnnotationTest {

    public enum TestEnum {

        OK("plugin.xml", "ok"),
        ALSO("", "also"),
        AND("", "and");

        private final String resourcePath;

        TestEnum(@ResourcePath(base = "META-INF") String resourcePath, String test) {
            this.resourcePath = resourcePath;
            testStaticMethod("plugin.xml");
        }

    }

    @ResourcePath
    private final String testField = "META-INF/plugin.xml";

    @ResourcePath(base = "META-INF")
    private final String testEnumField = TestEnum.OK.resourcePath;

    @ResourcePath
    private final String testConcatField = "META-INF/" + getName();

    @ResourcePath(base = "META-INF")
    private final String testBaseField = "plugin.xml";

    @ResourcePath(base = "META-INF")
    private final String testBaseMethodField = getName();

    private static void testStaticMethod(@ResourcePath(base = "META-INF") String resourcePath) { }

    private void testMethod(@ResourcePath String resourcePath) { }

    private void testMetaInfMethod(@ResourcePath(base = "META-INF") String resourcePath) { }

    private String testRecursiveMethod(String resourcePath2) {
        return resourcePath2 + getName();
    }

    @ResourcePath
    private String testReturnMethod(String resourcePath2) {
        return resourcePath2 + getFiletype();
    }

    final String getName() {
        return "plugin" + getFiletype();
    }

    final String getFiletype() {
        return ".xml";
    }

    @Test
    public void genericTest_ok() {
        String base = "META-INF/";
        String value = base + "plugin.xml";

        // Works
        testMethod(value);
        testMethod("META-INF/" + getName());
        testMetaInfMethod("plugin.xml");

        final String recursiveValue = testRecursiveMethod("META-INF/");
        testMethod(recursiveValue);
        final String returnValue = testReturnMethod("META-INF/plugin");
        testMethod(returnValue);

        MatcherAssert.assertThat("Done", true);
    }

}
