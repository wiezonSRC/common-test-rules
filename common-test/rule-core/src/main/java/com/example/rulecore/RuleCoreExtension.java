package com.example.rulecore;

import org.gradle.api.provider.Property;

public abstract class RuleCoreExtension {
    /**
     * Whether to enable the Spotless-based formatter.
     * Default is true.
     */
    public abstract Property<Boolean> getEnableFormatter();
}
