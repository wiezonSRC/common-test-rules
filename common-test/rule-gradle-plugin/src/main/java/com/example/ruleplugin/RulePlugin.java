package com.example.ruleplugin;

import com.diffplug.gradle.spotless.SpotlessExtension;
import com.diffplug.gradle.spotless.SpotlessPlugin;
import com.diffplug.spotless.extra.wtp.EclipseWtpFormatterStep;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RulePlugin implements Plugin<Project> {

    /**
     * 프로젝트에서 사용하는 태스크 명칭들을 Enum으로 관리합니다.
     */
    private enum RuleTaskName {
        RULE_CHECK("ruleCheck"),
        CHECK_ALL("checkAll"),
        CHECK("check"),
        SPOTLESS_APPLY("spotlessApply"),
        SPOTLESS_CHECK("spotlessCheck");

        private final String name;

        RuleTaskName(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    @Override
    public void apply(Project project) {
        // 1. Register Extension
        RuleExtension extension = project.getExtensions().create("rule", RuleExtension.class);
        
        // 기본값(Convention) 설정
        extension.getRuleGroupName().convention("ALL");
        
        // incremental 속성은 프로젝트 속성(rule.incremental)으로 오버라이드 가능하도록 설정
        extension.getIncremental().convention(project.getProviders().provider(() -> {
            if (project.hasProperty("rule.incremental")) {
                return Boolean.parseBoolean((String) project.property("rule.incremental"));
            }
            return true;
        }));
        
        extension.getFailOnViolation().convention(false);
        extension.getEnableFormatter().convention(true);

        // 2. Register Task
        // ./gradlew ruleCheck
        project.getTasks().register(RuleTaskName.RULE_CHECK.getName(), RuleTask.class, task -> task.setExtension(extension));

        // 3. Connect to 'check' task
        project.getTasks().named(RuleTaskName.CHECK.getName()).configure(t -> t.dependsOn(RuleTaskName.RULE_CHECK.getName()));

        // 4. Configure Spotless conditionally
        project.afterEvaluate(p -> {
            if (Boolean.TRUE.equals(extension.getEnableFormatter().getOrElse(false))) {
                configureSpotless(p);
            }
        });

        // 5. Register Combined Task (checkAll)
        project.getTasks().register(RuleTaskName.CHECK_ALL.getName(), task -> {
            task.setGroup("verification");
            task.setDescription("포맷팅(선택적)과 규칙 검사를 통합하여 실행합니다.");

            // spotlessApply는 enableFormatter 설정에 따라 조건부 의존성 추가
            task.dependsOn(project.getProviders().provider(() -> {
                List<Object> deps = new ArrayList<>();
                if (extension.getEnableFormatter().getOrElse(false)) {
                    Object spotlessApply = project.getTasks().findByName(RuleTaskName.SPOTLESS_APPLY.getName());
                    if (spotlessApply != null) {
                        deps.add(spotlessApply);
                    }
                }
                Object ruleCheck = project.getTasks().findByName(RuleTaskName.RULE_CHECK.getName());
                if (ruleCheck != null) {
                    deps.add(ruleCheck);
                }
                return deps;
            }));
        });

        // 6. Ensure order: ruleCheck runs after spotlessApply if both are in execution graph
        project.getTasks().withType(RuleTask.class).configureEach(ruleTask -> {
            ruleTask.mustRunAfter(project.getTasks().matching(t -> t.getName().equals(RuleTaskName.SPOTLESS_APPLY.getName())));
        });
    }

    private void configureSpotless(Project project) {
        project.getLogger().info("[RulePlugin] Configuring Spotless for project: {}", project.getName());
        
        project.getPluginManager().apply(SpotlessPlugin.class);
        SpotlessExtension spotless = project.getExtensions().getByType(SpotlessExtension.class);
        RuleExtension extension = project.getExtensions().getByType(RuleExtension.class);

        if (extension.getRatchetFrom().isPresent()) {
            spotless.ratchetFrom(extension.getRatchetFrom().get());
        }

        spotless.java(java -> {
            java.target("src/**/*.java");
            java.googleJavaFormat().aosp();
            java.removeUnusedImports();
            java.trimTrailingWhitespace();
            java.endWithNewline();
        });

        // XML 포맷팅 설정: SQL 키워드 대문자 변환 로직 포함
        spotless.format("xml", xml -> {
            xml.target("src/**/*.xml");
            xml.eclipseWtp(EclipseWtpFormatterStep.XML);
            xml.trimTrailingWhitespace();
            xml.indentWithSpaces(4);
            xml.endWithNewline();

            xml.custom("uppercaseSqlKeywords", content -> {
                // SQL 키워드 목록 (공백이 포함될 수 있는 키워드 고려)
                String keywordsPipe = "select|from|where|insert|update|delete|as|join|on|and|or|order\\s+by|group\\s+by|having|limit|offset|set|values|into|distinct|case|when|then|else|end";

                // 1. 주석: (<!--.*?-->)
                // 2. XML 태그: (<[^>]+>) - <if test="and"> 등 방어
                // 3. MyBatis 변수: ([#$]\\{.*?\\}) - #{select} 등 방어
                // 4. SQL 문자열 리터럴: ('(?:''|[^'])*') - 'select' 문자열 방어
                // 5. 키워드: ((?i)\\b(?:키워드들)\\b) - 실제 변환 대상
                Pattern pattern = Pattern.compile("(?s)(<!--.*?-->)|(<[^>]+>)|([#$]\\{.*?\\})|('(?:''|[^'])*')|((?i)\\b(" + keywordsPipe + ")\\b)");
                Matcher matcher = pattern.matcher(content);
                StringBuilder sb = new StringBuilder();

                while (matcher.find()) {
                    // 5번 그룹(키워드)이 매칭된 경우만 대문자로 변환
                    if (matcher.group(5) != null) {
                        matcher.appendReplacement(sb, matcher.group(5).toUpperCase());
                    } else {
                        // 주석, 태그, 변수 등은 그대로 유지 ($, \ 문자 등을 고려하여 quoteReplacement 사용)
                        matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
                    }
                }
                matcher.appendTail(sb);
                return sb.toString();
            });
        });
    }
}