package com.example.ruleplugin;

import com.diffplug.gradle.spotless.SpotlessExtension;
import com.diffplug.gradle.spotless.SpotlessPlugin;
import com.diffplug.spotless.extra.wtp.EclipseWtpFormatterStep;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

import java.util.Arrays;
import java.util.List;

public class RulePlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        // 1. Register Extension
        RuleExtension extension = project.getExtensions().create("rule", RuleExtension.class);
        extension.getEnableFormatter().convention(true);

        // 2. Register Task
        project.getTasks().register("ruleCheck", RuleTask.class, task -> {
            task.setExtension(extension);
        });

        // 3. Connect to 'check' task
        project.getTasks().named("check").configure(t -> t.dependsOn("ruleCheck"));

        // 4. Configure Spotless conditionally
        project.afterEvaluate(p -> {
            if (Boolean.TRUE.equals(extension.getEnableFormatter().get())) {
                configureSpotless(p);
            }
        });
    }

    private void configureSpotless(Project project) {
        project.getPluginManager().apply(SpotlessPlugin.class);
        SpotlessExtension spotless = project.getExtensions().getByType(SpotlessExtension.class);

        spotless.java(java -> {
            java.googleJavaFormat().aosp();
            java.removeUnusedImports();
            java.trimTrailingWhitespace();
            java.endWithNewline();
        });

        spotless.format("xml", xml -> {
            xml.target("**/*.xml");
            xml.eclipseWtp(EclipseWtpFormatterStep.XML);
            xml.trimTrailingWhitespace();
            xml.indentWithSpaces(4);
            xml.endWithNewline();

            xml.custom("uppercaseSqlKeywords", content -> {
                List<String> keywords = Arrays.asList(
                        "select", "from", "where", "insert", "update", "delete", "as", "join",
                        "on", "and", "or", "order by", "group by", "having", "limit", "offset",
                        "set", "values", "into", "distinct", "case", "when", "then", "else", "end"
                );

                String result = (String) content;
                for (String keyword : keywords) {
                    result = result.replaceAll("(?i)(?<![</])\b" + keyword + "\b", keyword.toUpperCase());
                }
                return result;
            });
        });
    }
}
