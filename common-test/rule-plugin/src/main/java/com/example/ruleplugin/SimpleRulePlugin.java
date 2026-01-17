package com.example.ruleplugin;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

import java.nio.file.Files;
import java.nio.file.Path;

public class SimpleRulePlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {

        project.afterEvaluate(p -> {
            try {
                generateGateTest(project);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void generateGateTest(Project project) throws Exception {

        Path genRoot = project.getBuildDir()
                .toPath()
                .resolve("generated/rule-gate-test");

        Path pkgDir = genRoot.resolve("generated");
        Files.createDirectories(pkgDir);

        Path testFile = pkgDir.resolve("RuleGateTest.java");

        Files.writeString(testFile, """
    package generated;

    import org.junit.jupiter.api.Test;

    public class RuleGateTest {

        @Test
        void quality_gate() throws Exception {
            Class<?> runner = Class.forName("com.example.rulecore.RuleRunner");
            runner.getMethod("runOrFail").invoke(null);
        }
    }
""");

        project.getExtensions()
                .getByType(org.gradle.api.tasks.SourceSetContainer.class)
                .getByName("test")
                .getJava()
                .srcDir(genRoot.toFile());
    }
}
