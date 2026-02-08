package com.example.rulecore.ruleEngine;

import com.example.rulecore.report.Report;
import com.example.rulecore.util.Status;

import java.util.ArrayList;
import java.util.List;

/**
 * 등록된 규칙들을 순차적으로 실행하고 결과를 취합하는 실행기입니다.
 */
public class RuleRunner {

    private final List<Rule> rules = new ArrayList<>();
    private final Report report = new Report();

    /**
     * 특정 규칙 그룹을 기반으로 실행기를 생성합니다.
     * @param group 실행할 규칙 묶음 (ALL, JAVA_STANDARD 등)
     */
    public RuleRunner(RuleGroups group) {
        this.rules.addAll(group.getRules());
    }


    /**
     * 규칙을 실행하고 결과에 따라 리포트를 생성합니다.
     * FAIL 등급의 위반 사항이 있을 경우 AssertionError를 던져 테스트를 실패 처리합니다.
     * 
     * @param context 검사 대상 환경 정보
     */
    public void runOrFail(RuleContext context) {
        List<RuleViolation> violations = run(context);
        // 결과에 따라 FAIL과 WARN 리포트를 각각 생성
        report.createFailReport(violations.stream().filter( violation -> violation.status() == Status.FAIL).toList());
        report.createWarnReport(violations.stream().filter( violation -> violation.status() == Status.WARN).toList());
    }

    /**
     * 모든 규칙을 순회하며 검사를 수행하고 위반 내역을 수집합니다.
     * 실행 중 예외가 발생한 규칙은 자동으로 FAIL 위반 사항으로 추가됩니다.
     * 
     * @param context 검사 대상 환경 정보
     * @return 발견된 모든 위반 사항 목록
     */
    public List<RuleViolation> run(RuleContext context) {
        List<RuleViolation> all = new ArrayList<>();

        for (Rule rule : rules) {
            try {
                all.addAll(rule.check(context));
            } catch (Exception e) {
                all.add(new RuleViolation(
                        rule.getClass().getSimpleName(),
                        Status.FAIL,
                        "Exception during rule execution: " + e.getMessage(),
                        ""
                ));
            }
        }

        return all;
    }

}
