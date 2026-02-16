package com.example.ruleplugin;

import com.diffplug.gradle.spotless.SpotlessExtension;
import com.diffplug.gradle.spotless.SpotlessPlugin;
import com.diffplug.spotless.extra.wtp.EclipseWtpFormatterStep;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RulePlugin implements Plugin<Project> {

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
        project.getTasks().register("ruleCheck", RuleTask.class, task -> task.setExtension(extension));

        // 3. Connect to 'check' task
        project.getTasks().named("check").configure(t -> t.dependsOn("ruleCheck"));

        // 4. Configure Spotless conditionally
        project.afterEvaluate(p -> {
            if (Boolean.TRUE.equals(extension.getEnableFormatter().getOrElse(false))) {
                configureSpotless(p);
            }
        });

        // 5. Register Combined Task (checkAll)
        project.getTasks().register("checkAll", task -> {
            task.setGroup("verification");
            task.setDescription("포맷팅(선택적)과 규칙 검사를 통합하여 실행합니다.");

            // spotlessApply는 enableFormatter 설정에 따라 조건부 의존성 추가
            task.dependsOn(project.getProviders().provider(() -> {
                List<Object> deps = new ArrayList<>();
                if (extension.getEnableFormatter().getOrElse(false)) {
                    Object spotlessApply = project.getTasks().findByName("spotlessApply");
                    if (spotlessApply != null) {
                        deps.add(spotlessApply);
                    }
                }
                Object ruleCheck = project.getTasks().findByName("ruleCheck");
                if (ruleCheck != null) {
                    deps.add(ruleCheck);
                }
                return deps;
            }));
        });

        // 6. Ensure order: ruleCheck runs after spotlessApply if both are in execution graph
        project.getTasks().withType(RuleTask.class).configureEach(ruleTask -> {
            ruleTask.mustRunAfter(project.getTasks().matching(t -> t.getName().equals("spotlessApply")));
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

        // FIXME) 키워드 대문자시, 주석 혹은 <if test= > 태그안의 내용도 키워드 단어가 있다면 치환될 수 있는 위험이 존재 확인필요
        spotless.format("xml", xml -> {
            xml.target("src/**/*.xml");
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

                String result = content;
                for (String keyword : keywords) {
                    result = result.replaceAll("(?i)(?<![</])\\b" + keyword + "\\b", keyword.toUpperCase());
                }
                return result;
            });
        });
    }
}
