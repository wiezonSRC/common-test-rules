package com.example.rulecore.ruleEngine;

import com.example.rulecore.rules.java.fail.*;
import com.example.rulecore.rules.sql.fail.*;
import com.example.rulecore.rules.style.warn.JavaNamingRule;
import com.example.rulecore.rules.style.fail.SqlStyleRule;

import java.util.Arrays;
import java.util.List;

/**
 * 관련 있는 규칙들을 논리적인 그룹으로 묶어 관리하는 Enum입니다.
 * Composite 패턴의 아이디어를 활용하여 여러 규칙을 하나의 단위로 다룰 수 있게 합니다.
 */
public enum RuleGroups {

    /**
     * Java 표준 컨벤션 그룹 (네이밍, 구조, 안전성)
     */
    JAVA_STANDARD(
            new JavaNamingRule(),
            new NoSystemOutRule(),
            new NoFieldInjectionRule(),
            new JavaStandardRule(),
            new JavaConstantsRule(),
            new NoGenericCatchRule(),
            new LombokUsageRule()
    ),

    /**
     * SQL 안전성 및 스타일 그룹 (MyBatis ${} 금지, Transaction 예외 처리, SQL 안티패턴)
     */
    SQL_SAFETY_AND_STYLE(
            new TransactionalSwallowExceptionRule(),
            new NoDollarExpressionRule(),
            new SqlStyleRule(),
            new MyBatisXmlRule(),
            new SqlBasicPerformanceRule()
    ),

    /**
     * 프로젝트의 모든 규칙을 실행하는 그룹
     */
    ALL(
            new JavaNamingRule(),
            new NoSystemOutRule(),
            new NoFieldInjectionRule(),
            new JavaStandardRule(),
            new JavaConstantsRule(),
            new NoGenericCatchRule(),
            new LombokUsageRule(),
            new TransactionalSwallowExceptionRule(),
            new NoDollarExpressionRule(),
            new SqlStyleRule(),
            new MyBatisXmlRule(),
            new SqlBasicPerformanceRule()
    );

    private final List<Rule> rules;

    RuleGroups(Rule... rules) {
        this.rules = Arrays.asList(rules);
    }

    /**
     * 그룹에 포함된 모든 규칙 목록을 반환합니다.
     */
    public List<Rule> getRules() {
        return rules;
    }
}