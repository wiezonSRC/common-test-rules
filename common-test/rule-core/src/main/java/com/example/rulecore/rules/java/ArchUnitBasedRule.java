package com.example.rulecore.rules.java;

import com.example.rulecore.ruleEngine.Rule;
import com.example.rulecore.ruleEngine.RuleContext;
import com.example.rulecore.ruleEngine.RuleViolation;

import com.example.rulecore.ruleEngine.enums.Status;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.FailureReport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ArchUnit 기반 Java 규칙의 추상 클래스입니다. (1순위 집중)
 */
public abstract class ArchUnitBasedRule implements Rule {

    protected abstract ArchRule getDefinition();
    
    protected String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public List<RuleViolation> check(RuleContext context) {
        List<RuleViolation> violations = new ArrayList<>();

        JavaClasses classes;
        if (context.hasAffectedFiles()) {
            // 증분 검증: 변경된 파일 중 .java에 해당하는 .class 경로만 추출
            List<Path> targetPaths = context.affectedFiles().stream()
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(this::resolveClassPath)
                    .filter(p -> p != null && p.toFile().exists())
                    .collect(Collectors.toList());

            if (targetPaths.isEmpty()) return violations;
            classes = new ClassFileImporter().importPaths(targetPaths);
        } else {
            // 전수 검증
            classes = new ClassFileImporter().importPackages(context.basePackage());
        }

        if (classes.isEmpty()) return violations;

        FailureReport failureReport = getDefinition().evaluate(classes).getFailureReport();

        failureReport.getDetails().forEach(event ->
                violations.add(new RuleViolation(
                getName(),
                Status.FAIL,
                event,
                null,
                0
        )));

        return violations;
    }

    /**
     * .java 파일 경로를 빌드된 .class 파일 경로로 변환 시도
     */
    private Path resolveClassPath(Path javaFile) {
        String pathStr = javaFile.toAbsolutePath().toString().replace("\\", "/");
        if (pathStr.contains("/src/main/java/")) {
            String[] parts = pathStr.split("/src/main/java/");
            String moduleRoot = parts[0];
            String relativeClassPath = parts[1].replace(".java", ".class");
            
            Path classFile = Path.of(moduleRoot, "build", "classes", "java", "main", relativeClassPath);
            if (classFile.toFile().exists()) {
                return classFile;
            }
        }
        return null;
    }
}