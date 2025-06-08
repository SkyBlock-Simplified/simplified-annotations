package dev.sbs.annotation;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that the annotated string field, method parameter, or enum constructor parameter
 * represents a resource path that should exist in the resource directory.
 *
 * <pre><code>
 *   // Class Field
 *   &#64;ResourcePath
 *   private String configFile = "config/settings.xml";
 *
 *   // Class Field (with base)
 *   &#64;ResourcePath(base = "images")
 *   private String iconFile = "icon.png"; // images/icon.png
 *
 *   // Method Parameter (with base)
 *   public void loadResource(&#64;ResourcePath(base = "templates") String fileName) {
 *       // implementation
 *   }
 *
 *   // Enum Constructor
 *   public enum Status {
 *       OK("logo.png"); // assets/logo.png
 *
 *       private final String iconPath;
 *
 *       Status(&#64;ResourcePath(base = "assets") String iconPath) {
 *           this.iconPath = iconPath;
 *       }
 *   }
 * </code></pre>
 */
@Retention(RetentionPolicy.CLASS)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD })
public @interface ResourcePath {

    /**
     * Optional base folder under which the resource path is resolved.
     * Defaults to the resources directory if not specified.
     *
     * @return the base folder path prefix
     */
    @NotNull String base() default "";

}
