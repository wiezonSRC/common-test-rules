package com.example.rulecore.rules.java.fail;

import com.example.rulecore.rules.java.ArchUnitBasedRule;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

public class NoSystemOutRule extends ArchUnitBasedRule {

    @Override
    protected ArchRule getDefinition() {
        return ArchRuleDefinition.noClasses()
                .should().accessClassesThat().belongToAnyOf(System.class)
                .as(" System.out 일반 출력 금지 ");
    }
}
