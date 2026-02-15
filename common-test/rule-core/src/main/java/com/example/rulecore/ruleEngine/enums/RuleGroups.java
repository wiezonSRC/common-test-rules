package com.example.rulecore.ruleEngine.enums;

import com.example.rulecore.ruleEngine.Rule;
import com.example.rulecore.rules.java.fail.*;
import com.example.rulecore.rules.sql.fail.*;

import java.util.Arrays;
import java.util.List;

/**
 * 관련 있는 규칙들을 논리적인 그룹으로 묶어 관리하는 Enum입니다.
 */
public enum RuleGroups {

    JAVA_CRITICAL(
            new NoSystemOutRule(),
            new NoFieldInjectionRule(),
            new NoGenericCatchRule()
    ),

    SQL_CRITICAL(
            new TransactionalSwallowExceptionRule(),
            new NoDollarExpressionRule(),
            new MyBatisXmlRule(),
            new MyBatisIfRule(),
            new SqlBasicPerformanceRule()
    ),

    ALL(
            new NoSystemOutRule(),
            new NoFieldInjectionRule(),
            new NoGenericCatchRule(),
            new TransactionalSwallowExceptionRule(),
            new NoDollarExpressionRule(),
            new MyBatisXmlRule(),
            new MyBatisIfRule(),
            new SqlBasicPerformanceRule()
    );

    private final List<Rule> rules;
    RuleGroups(Rule... rules) {
        this.rules = Arrays.asList(rules);
    }

    public List<Rule> getRules() {
        return rules;
    }
}
