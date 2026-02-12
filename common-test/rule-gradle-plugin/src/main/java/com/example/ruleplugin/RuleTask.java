package com.example.ruleplugin;

import com.example.rulecore.ruleEngine.RuleContext;
import com.example.rulecore.ruleEngine.RuleResult;
import com.example.rulecore.ruleEngine.RuleRunner;
import com.example.rulecore.util.GitDiffUtil;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.TaskAction;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class RuleTask extends DefaultTask {

    private RuleExtension extension;

    public void setExtension(RuleExtension extension) {
        this.extension = extension;
    }

    @TaskAction
    public void execute() {
        Path projectRoot = getProject().getProjectDir().toPath();
        
        List<Path> affectedFiles = new ArrayList<>();
        if (extension.isIncremental()) {
            affectedFiles = GitDiffUtil.getAffectedFiles(projectRoot);
        }

        // Search for mapper XMLs in common locations if needed, or let user configure
        // For now, let's just use src/main/resources/mapper
        List<Path> mapperDirs = new ArrayList<>();
        Path defaultMapperDir = projectRoot.resolve("src/main/resources/mapper");
        if (defaultMapperDir.toFile().exists()) {
            mapperDirs.add(defaultMapperDir);
        }

        RuleContext context = RuleContext.builder()
                .basePackage(extension.getBasePackage())
                .projectRoot(projectRoot)
                .mapperDirs(mapperDirs)
                .affectedFiles(affectedFiles)
                .build();

        Path reportDir = getProject().getBuildDir().toPath().resolve("reports/rule");
        RuleResult result = RuleRunner.run(context, reportDir);

        if (extension.isFailOnError() && result.hasError()) {
            throw new GradleException("Rule violations detected.");
        }
    }
}
