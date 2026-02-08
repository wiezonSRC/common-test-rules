package com.example.rulecore;

import com.diffplug.gradle.spotless.SpotlessExtension;
import com.diffplug.gradle.spotless.SpotlessPlugin;
import com.diffplug.spotless.extra.wtp.EclipseWtpFormatterStep;
import java.util.Arrays;
import java.util.List;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class RuleCorePlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        // 1. Register Extension
        RuleCoreExtension extension = project.getExtensions().create("ruleCore", RuleCoreExtension.class);
        extension.getEnableFormatter().convention(true);

        // 2. Configure Spotless conditionally after project evaluation
        project.afterEvaluate(p -> {
            if (Boolean.TRUE.equals(extension.getEnableFormatter().get())) {
                configureSpotless(p);
            }
        });
    }

    private void configureSpotless(Project project) {
        // 1. Apply Spotless Plugin
        project.getPluginManager().apply(SpotlessPlugin.class);

        // 2. Configure Spotless Extension
        SpotlessExtension spotless = project.getExtensions().getByType(SpotlessExtension.class);

        // Java Format
        spotless.java(java -> {
            java.googleJavaFormat().aosp();
            java.removeUnusedImports();
            java.trimTrailingWhitespace();
            java.endWithNewline();
        });

        // XML Format (MyBatis)
        spotless.format("xml", xml -> {
            xml.target("**/*.xml");
            xml.eclipseWtp(EclipseWtpFormatterStep.XML);
            xml.trimTrailingWhitespace();
            xml.indentWithSpaces(4);
            xml.endWithNewline();

            // Custom Step: Uppercase SQL Keywords
            xml.custom("uppercaseSqlKeywords", content -> {
                List<String> keywords = Arrays.asList(
                    "select", "from", "where", "insert", "update", "delete", "as", "join",
                    "on", "and", "or", "order by", "group by", "having", "limit", "offset",
                    "set", "values", "into", "distinct", "case", "when", "then", "else", "end"
                );
                
                String result = (String) content;
                for (String keyword : keywords) {
                    result = result.replaceAll("(?i)(?<![</])\\b" + keyword + "\\b", keyword.toUpperCase());
                }
                return result;
            });
        });
    }
}