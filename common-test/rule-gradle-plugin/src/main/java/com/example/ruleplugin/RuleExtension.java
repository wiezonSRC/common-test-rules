package com.example.ruleplugin;

import org.gradle.api.provider.Property;

public abstract class RuleExtension {
    private String basePackage;
    private boolean incremental = true;
    private boolean failOnError = false;

    public abstract Property<Boolean> getEnableFormatter();

    public String getBasePackage() {
        return basePackage;
    }

    public void setBasePackage(String basePackage) {
        this.basePackage = basePackage;
    }

    public boolean isIncremental() {
        return incremental;
    }

    public void setIncremental(boolean incremental) {
        this.incremental = incremental;
    }

    public boolean isFailOnError() {
        return failOnError;
    }

    public void setFailOnError(boolean failOnError) {
        this.failOnError = failOnError;
    }
}
