package com.example.ruleplugin;

import com.example.rulecore.ruleEngine.RuleContext;

import com.example.rulecore.ruleEngine.RuleResult;
import com.example.rulecore.ruleEngine.RuleRunner;
import com.example.rulecore.ruleEngine.enums.RuleGroups;
import com.example.rulecore.util.GitDiffUtil;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.TaskAction;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class RuleTask extends DefaultTask {

    private RuleExtension extension;

    public void setExtension(RuleExtension extension) {
        this.extension = extension;
    }

    @TaskAction
    public void execute() {
        // 필수 설정 체크
        if (!extension.getBasePackage().isPresent()) {
            throw new GradleException("Property 'basePackage' is required in 'rule' extension.");
        }

        Path projectRoot = getProject().getProjectDir().toPath();
        
        // 1. 증분 검사 대상 수집
        List<Path> affectedFiles = new ArrayList<>();
        if (extension.getIncremental().get()) {
            affectedFiles = GitDiffUtil.getAffectedFiles(projectRoot);
        }

        // 2. 매퍼 디렉토리 설정
        List<Path> mapperDirs = new ArrayList<>();
        List<String> customMapperPaths = extension.getMapperPaths().getOrElse(new ArrayList<>());
        
        if (customMapperPaths.isEmpty()) {
            Path defaultMapperDir = projectRoot.resolve("src/main/resources/mapper");
            if (defaultMapperDir.toFile().exists()) {
                mapperDirs.add(defaultMapperDir);
            }
        } else {
            mapperDirs = customMapperPaths.stream()
                    .map(projectRoot::resolve)
                    .filter(p -> p.toFile().exists())
                    .collect(Collectors.toList());
        }

        // 3. 컨텍스트 빌드
        RuleContext context = RuleContext.builder()
                .basePackage(extension.getBasePackage().get())
                .projectRoot(projectRoot)
                .mapperDirs(mapperDirs)
                .affectedFiles(affectedFiles)
                .build();


        // 4. Rule Group 찾기
        String groupName = extension.getRuleGroupName().get().toUpperCase();
        RuleGroups group;
        try {
            group = RuleGroups.valueOf(groupName);
        } catch (IllegalArgumentException e) {
            getLogger().warn("Invalid rule group name: {}. Falling back to ALL.", groupName);
            group = RuleGroups.ALL;
        }

        // 5. Rule check 실행
        Path reportDir = getProject().getProjectDir().toPath().resolve("reports/rule");
        RuleRunner runner = new RuleRunner(group);
        RuleResult result = RuleRunner.run(runner, context, reportDir);

        // 6. 실패 시 처리
        if (extension.getFailOnViolation().get() && result.hasError()) {
            throw new GradleException("Critical rule violations detected. See reports in: " + reportDir);
        }
    }
}
