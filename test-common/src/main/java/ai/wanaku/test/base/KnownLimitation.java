package ai.wanaku.test.base;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test that is expected to skip due to a known product limitation.
 * Skips from annotated tests are excluded from the {@link SkipThresholdExtension} ratio.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface KnownLimitation {

    /**
     * Reference to the tracking issue (e.g., "wanaku#1741").
     */
    String value();
}
