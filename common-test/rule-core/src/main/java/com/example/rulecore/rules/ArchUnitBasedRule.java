package com.example.rulecore.rules;

import com.example.rulecore.ruleEngine.Rule;
import com.example.rulecore.ruleEngine.RuleContext;
import com.example.rulecore.ruleEngine.RuleViolation;
import com.example.rulecore.util.Status;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;

import java.util.ArrayList;
import java.util.List;

public abstract class ArchUnitBasedRule implements Rule {

    protected abstract ArchRule getDefinition();
    
    // 룰 이름을 클래스명이나 별도 메서드로 정의
    protected String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public List<RuleViolation> check(RuleContext context) {
        List<RuleViolation> violations = new ArrayList<>();

        // Context의 basePackage를 기준으로 클래스 임포트
        // 성능 최적화를 위해 캐싱 고려 가능하지만, 지금은 단순하게 매번 임포트
        JavaClasses classes = new ClassFileImporter()
                .importPackages(context.getBasePackage());

        getDefinition().evaluate(classes).getFailureReport().getDetails().forEach(failure -> {
            violations.add(new RuleViolation(
                    getName(),
                    Status.WARN,
                    failure, // ArchUnit의 상세 실패 메시지
                    "" // 위치 정보는 메시지에 포함되어 있음
            ));
        });

        return violations;
    }
}
