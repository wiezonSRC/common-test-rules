package com.example.rulecore.rules.java.fail;

import com.example.rulecore.rules.ArchUnitBasedRule;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

public class JavaStandardRule extends ArchUnitBasedRule {

    @Override
    protected ArchRule getDefinition() {
        // 1. Interface 이름에 'I' 접두사 금지
        ArchRule noIPrefix = ArchRuleDefinition.noClasses()
                .that().areInterfaces()
                .should().haveSimpleNameStartingWith("I")
                .andShould().haveSimpleNameNotStartingWith("I_") // I_ 로 시작하는것도 방지
                .allowEmptyShould(true)
                .as("Interfaces must not be prefixed with 'I'");

        // 2. 구현체는 'Impl' 접미사 필수 (인터페이스를 구현한 클래스 중, Service/Repository 등 주요 컴포넌트)
        // 일반적인 모든 구현체를 강제하기는 어려우므로, 관례적으로 ServiceImpl 등을 체크하거나
        // 반대로 'Impl'로 끝나는 클래스는 반드시 인터페이스를 구현해야 한다던지 등의 규칙이 가능.
        // 여기서는 "Service"로 끝나는 인터페이스의 구현체는 "ServiceImpl"로 끝나야 한다 등으로 구체화가 필요하지만,
        // 단순화하여 "Impl"로 끝나는 클래스는 인터페이스여서는 안된다(클래스여야 한다) 정도로 시작.
        ArchRule implSuffix = ArchRuleDefinition.classes()
                .that().haveSimpleNameEndingWith("Impl")
                .should().notBeInterfaces()
                .allowEmptyShould(true)
                .as("Classes ending with 'Impl' must be concrete classes, not interfaces");

        // 3. Service 클래스는 'Service' 접미사
        ArchRule serviceSuffix = ArchRuleDefinition.classes()
                .that().resideInAPackage("..service..")
                .and().areAnnotatedWith("org.springframework.stereotype.Service")
                .should().haveSimpleNameEndingWith("Service")
                .allowEmptyShould(true)
                .as("Service classes must end with 'Service'");

        return CompositeArchRule.of(noIPrefix)
                .and(implSuffix)
                .and(serviceSuffix);
    }
}
