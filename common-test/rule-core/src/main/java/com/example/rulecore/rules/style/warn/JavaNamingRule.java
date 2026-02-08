package com.example.rulecore.rules.style.warn;

import com.example.rulecore.rules.ArchUnitBasedRule;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.springframework.stereotype.Service;

public class JavaNamingRule extends ArchUnitBasedRule {

    @Override
    protected ArchRule getDefinition() {
        // 1.2 클래스 (Classes): PascalCase 및 역할별 접미사
        ArchRule pascalRule = ArchRuleDefinition.classes()
                .that().areNotAnonymousClasses()
                .should().haveNameMatching(".*\\.[A-Z][a-zA-Z0-9$]*$")
                .as("클래스는 PascalCase 사용");
        
        ArchRule serviceSuffixRule = ArchRuleDefinition.classes()
                .that().areAnnotatedWith(Service.class)
                .should().haveSimpleNameEndingWith("Service")
                .as("서비스 클래스는 'service' 접미사 사용");

        // 1.3 인터페이스 (Interfaces): PascalCase, 'I' 접두사 지양
        ArchRule interfaceRule = ArchRuleDefinition.classes()
                .that().areInterfaces()
                .should().haveNameMatching(".*\\.[A-Z][a-zA-Z0-9]*$")
                .andShould().haveNameMatching(".*\\.(?!I[A-Z])[a-zA-Z0-9]*$")
                .allowEmptyShould(true)
                .as("인터페이스는 PascalCase 사용, 접두엇 'I' 사용 x");

        // 1.4 메소드 (Methods): camelCase
        ArchRule methodRule = ArchRuleDefinition.methods()
                // .that().areNotAnnotatedWith(Test.class) // 라이브러리에서는 Test 어노테이션 의존성 없으므로 이름 패턴으로 제외하거나 생략
                .should().haveNameMatching("^[a-z][a-zA-Z0-9]*$")
                .allowEmptyShould(true)
                .as("메서드 이름은 camelCase 사용");

        // 1.5 변수 (Variables - Fields): camelCase
        ArchRule fieldRule = ArchRuleDefinition.fields()
                .that().areNotStatic().or().areNotFinal()
                .should().haveNameMatching("^[a-z][a-zA-Z0-9$]*$")
                .allowEmptyShould(true)
                .as("필드명 camelCase 사용");

        // 1.6 상수 (Constants): static final은 SNAKE_CASE
        ArchRule constantRule = ArchRuleDefinition.fields()
                .that().areStatic().and().areFinal()
                .should().haveNameMatching("^[A-Z][A-Z0-9_]*$")
                .allowEmptyShould(true)
                .as("상수는 대문자 SNAKE_CASE 사용");

        return CompositeArchRule.of(pascalRule)
                .and(serviceSuffixRule)
                .and(interfaceRule)
                .and(methodRule)
                .and(fieldRule)
                .and(constantRule);
    }
}