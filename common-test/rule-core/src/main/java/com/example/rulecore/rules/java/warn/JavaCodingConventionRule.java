package com.example.rulecore.rules.java.warn;

import com.example.rulecore.rules.ArchUnitBasedRule;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

import java.util.Set;

public class JavaCodingConventionRule extends ArchUnitBasedRule {

    @Override
    protected ArchRule getDefinition() {
        
        // 1. 변수(필드) 이름 길이 검사: 2글자 이하 금지 (단, 'id' 등 예외 허용)
        // 로컬 변수는 ArchUnit으로 검사 어려움(바이트코드 레벨). 필드 위주로 검사.
        Set<String> allowedShortNames = Set.of("id", "no", "ip", "os", "ui");

        ArchRule shortFieldNameRule = ArchRuleDefinition.fields()
                .that().areNotStatic() // static 상수는 제외
                .should(new ArchCondition<>("have name length >= 3") {
                    @Override
                    public void check(com.tngtech.archunit.core.domain.JavaField field, ConditionEvents events) {
                        String name = field.getName();
                        if (name.length() < 3 && !allowedShortNames.contains(name.toLowerCase())) {
                            events.add(SimpleConditionEvent.violated(field,
                                    "Field '" + name + "' is too short. Use descriptive names (>= 3 chars)."));
                        }
                    }
                })
                .allowEmptyShould(true)
                .as("Field names should be descriptive (>= 3 chars)");

        // 2. 메서드 파라미터 final 권장 (불변성)
        ArchRule finalParamRule = ArchRuleDefinition.methods()
                .should(new ArchCondition<>("have final parameters") {
                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        // 인터페이스나 추상 메서드는 제외
                        if (method.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.ABSTRACT)) return;

                        for (JavaParameter param : method.getParameters()) {
                            // ArchUnit 1.0+ 에서는 reflect().isFinal() 사용 가능 또는 getModifiers() 확인
                            // JavaParameter는 modifiers를 직접 노출하지 않을 수 있음.
                            // 리플렉션이나 ASM 기반 정보가 필요함.
                            // ArchUnit의 JavaParameter는 현재 modifier 정보를 완벽히 제공하지 않을 수 있음 (버전 의존적).
                            // 여기서는 "권장" 수준이므로, 만약 확인 불가능하면 패스.
                            // (ArchUnit 1.3.0 기준 JavaParameter.getOwner().getParameters()...)
                            // 구현의 복잡도 대비 효과가 적을 수 있어, 
                            // 일단은 "파라미터 final" 검사는 ASM 레벨이 더 정확하므로 여기서는 생략하거나
                            // 다른 방식으로 접근. 여기서는 스킵.
                        }
                    }
                })
                .allowEmptyShould(true)
                .as("Method parameters should be final (Not implemented in this rule due to complexity)");

        // 3. ENUM 사용 권장 (단순 String 상수로 상태 관리하는 것 지양)
        // -> 상수로 "STATUS_" 등으로 시작하는 String들이 많으면 경고?
        ArchRule stringConstantRule = ArchRuleDefinition.fields()
                .that().areStatic().and().areFinal()
                .and().haveRawType(String.class)
                .should(new ArchCondition<>("not look like enum substitute") {
                    @Override
                    public void check(com.tngtech.archunit.core.domain.JavaField field, ConditionEvents events) {
                        if (field.getName().startsWith("STATUS_") || field.getName().startsWith("TYPE_")) {
                            events.add(SimpleConditionEvent.violated(field,
                                    "Constant '" + field.getName() + "' looks like an Enum substitute. Consider using Java Enum."));
                        }
                    }
                })
                .allowEmptyShould(true)
                .as("Prefer Enum over String constants for Status/Type");

        return CompositeArchRule.of(shortFieldNameRule)
                .and(stringConstantRule);
    }
}
