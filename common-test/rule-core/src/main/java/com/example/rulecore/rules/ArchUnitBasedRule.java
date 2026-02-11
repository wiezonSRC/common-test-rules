package com.example.rulecore.rules;

import com.example.rulecore.ruleEngine.Rule;
import com.example.rulecore.ruleEngine.RuleContext;
import com.example.rulecore.ruleEngine.RuleViolation;
import com.example.rulecore.util.Status;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.FailureReport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ArchUnit 라이브러리를 기반으로 Java 코드의 구조와 컨벤션을 검사하는 기본 클래스입니다.
 * 정적 분석(바이트코드 분석)을 통해 클래스 간 의존성, 패키지 구조, 네이밍 등을 검증합니다.
 */
public abstract class ArchUnitBasedRule implements Rule {

    /**
     * 구체적인 ArchUnit 규칙(ArchRule)을 정의합니다.
     * 상속받는 클래스에서 이 메서드를 구현하여 실제 검증 로직을 작성합니다.
     * 
     * @return 정의된 ArchRule 객체
     */
    protected abstract ArchRule getDefinition();
    
    /**
     * 규칙의 이름을 반환합니다. 기본값은 클래스 명입니다.
     */
    protected String getName() {
        return this.getClass().getSimpleName();
    }

    /**
     * Context에 지정된 패키지 경로를 스캔하여 ArchUnit 규칙을 평가합니다.
     */
    @Override
    public List<RuleViolation> check(RuleContext context) {
        List<RuleViolation> violations = new ArrayList<>();

        JavaClasses classes;
        if (context.hasAffectedFiles()) {
            // 변경된 파일만 대상으로 임포트 (증분 검사)
            // .java 파일 경로를 ArchUnit이 인식할 수 있는 형태로 변환하거나
            // 프로젝트 루트에서 해당 파일들만 필터링하여 임포트
            classes = new ClassFileImporter()
                    .importPaths(context.affectedFiles());
        } else {
            // 전체 패키지 대상 (전수 검사)
            classes = new ClassFileImporter()
                    .importPackages(context.basePackage());
        }

        if (classes.isEmpty()) {
            return violations;
        }

        FailureReport failureReport = getDefinition().evaluate(classes).getFailureReport();

        failureReport.getDetails().forEach(event -> {
            // ArchUnit 이벤트 메시지에서 (FileName.java:Line) 패턴을 추출하거나 전체 메시지 사용
            String filePath = null;
            Integer lineNumber = null;

            // 소스 위치 정보가 있는 경우 추출 시도 (간단한 파싱)
            // 보통 "in (FileName.java:10)" 형태를 가짐
            if (event.contains("(") && event.contains(")")) {
                try {
                    String locationPart = event.substring(event.lastIndexOf("(") + 1, event.lastIndexOf(")"));
                    if (locationPart.contains(":")) {
                        String[] parts = locationPart.split(":");
                        filePath = parts[0].trim();
                        lineNumber = Integer.parseInt(parts[1].trim());
                    }
                } catch (Exception ignored) {
                    // 파싱 실패 시 기본값 유지
                }
            }

            violations.add(new RuleViolation(
                    getName(),
                    Status.WARN,
                    event,
                    filePath,
                    lineNumber
            ));
        });

        return violations;
    }
}
