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
                .as("Classes should be PascalCase");
        
        ArchRule serviceSuffixRule = ArchRuleDefinition.classes()
                .that().areAnnotatedWith(Service.class)
                .should().haveSimpleNameEndingWith("Service")
                .as("Service classes should end with 'Service'");

        // 1.3 인터페이스 (Interfaces): PascalCase, 'I' 접두사 지양
        ArchRule interfaceRule = ArchRuleDefinition.classes()
                .that().areInterfaces()
                .should().haveNameMatching(".*\\.[A-Z][a-zA-Z0-9]*$")
                .andShould().haveNameMatching(".*\\.(?!I[A-Z])[a-zA-Z0-9]*$")
                .allowEmptyShould(true)
                .as("Interfaces should be PascalCase and not start with 'I'");

        // 1.4 메소드 (Methods): camelCase
        ArchRule methodRule = ArchRuleDefinition.methods()
                // .that().areNotAnnotatedWith(Test.class) // 라이브러리에서는 Test 어노테이션 의존성 없으므로 이름 패턴으로 제외하거나 생략
                .should().haveNameMatching("^[a-z][a-zA-Z0-9]*$")
                .allowEmptyShould(true)
                .as("Methods should be camelCase");

        // 1.5 변수 (Variables - Fields): camelCase
        ArchRule fieldRule = ArchRuleDefinition.fields()
                .that().areNotStatic().or().areNotFinal()
                .should().haveNameMatching("^[a-z][a-zA-Z0-9$]*$")
                .allowEmptyShould(true)
                .as("Fields should be camelCase");

        // 1.6 상수 (Constants): static final은 SNAKE_CASE
        ArchRule constantRule = ArchRuleDefinition.fields()
                .that().areStatic().and().areFinal()
                .should().haveNameMatching("^[A-Z][A-Z0-9_]*$")
                .allowEmptyShould(true)
                .as("Constants should be SNAKE_CASE");

        return CompositeArchRule.of(pascalRule)
                .and(serviceSuffixRule)
                .and(interfaceRule)
                .and(methodRule)
                .and(fieldRule)
                .and(constantRule);
    }
}