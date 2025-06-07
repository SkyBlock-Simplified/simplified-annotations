package dev.sbs.annotation;

import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.Test;

public class AnnotationTest {

    @ResourcePath
    private final String testField = "META-INF/plugin.xml";

    private void testMethod(@ResourcePath(base = "META-INF") String resourcePath) {

    }

    public enum TestEnum {

        OK("plugin.xml"),
        ALSO("pluginIcon.svg");

        private final String resourcePath;

        TestEnum(@ResourcePath(base = "META-INF") String resourcePath) {
            this.resourcePath = resourcePath;
        }

    }

    @Test
    public void genericTest_ok() {
        testMethod("genericTest_ok");
        MatcherAssert.assertThat("Done", true);
    }

}
