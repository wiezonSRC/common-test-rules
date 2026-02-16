package com.example.rulecore.ruleEngine;

import com.example.rulecore.ruleEngine.enums.Status;

import java.util.List;

/**
 * 규칙 검사 전체 실행 결과를 담는 객체입니다.
 * 총 실행 시간(ms) 정보를 포함합니다.
 */
public record RuleResult(
        List<RuleViolation> violations,
        long executionTimeMillis
) {
    public boolean hasError() {
        return violations.stream()
                .anyMatch(
                        violation -> violation.status() == Status.FAIL
                );
    }
}
