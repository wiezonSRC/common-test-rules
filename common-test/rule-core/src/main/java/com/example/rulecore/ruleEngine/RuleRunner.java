package com.example.rulecore.ruleEngine;

import com.example.rulecore.report.Report;
import com.example.rulecore.ruleEngine.enums.RuleGroups;
import com.example.rulecore.ruleEngine.enums.Status;


import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 등록된 규칙들을 순차적으로 실행하고 결과를 취합하는 실행기입니다.
 */
public class RuleRunner {

    private final List<Rule> rules = new ArrayList<>();

    /**
     * 특정 규칙 그룹을 기반으로 실행기를 생성합니다.
     * @param group 실행할 규칙 묶음 (JAVA_CRITICAL, SQL_CRITICAL, ALL 등)
     */
    public RuleRunner(RuleGroups group) {
        this.rules.addAll(group.getRules());
    }

    /**
     * 지정된 RuleRunner 인스턴스를 사용하여 검사를 수행하고 리포트를 생성합니다.
     * 
     * @param runner 이미 초기화된 RuleRunner
     * @param context 검사 대상 환경 정보
     * @param outputDir 리포트 저장 경로
     */
    public static RuleResult run(RuleRunner runner, RuleContext context, Path outputDir) {
        List<RuleViolation> violations = runner.executeRules(context);
        
        Report report = new Report(outputDir);
        report.createFailReport(violations.stream().filter(v -> v.status() == Status.FAIL).toList());
        report.createWarnReport(violations.stream().filter(v -> v.status() == Status.WARN).toList());
        
        return new RuleResult(violations);
    }

    /**
     * 모든 규칙을 순회하며 검사를 수행하고 위반 내역을 수집합니다.
     * 
     * @param context 검사 대상 환경 정보
     * @return 발견된 모든 위반 사항 목록
     */
    public List<RuleViolation> executeRules(RuleContext context) {
        List<RuleViolation> all = new ArrayList<>();

        for (Rule rule : rules) {
            try {
                all.addAll(rule.check(context));
            } catch (Exception e) {
                all.add(new RuleViolation(
                        rule.getClass().getSimpleName(),
                        Status.FAIL,
                        "Exception during rule execution: " + e.getMessage(),
                        "",
                        0
                ));
            }
        }

        return all;
    }
}