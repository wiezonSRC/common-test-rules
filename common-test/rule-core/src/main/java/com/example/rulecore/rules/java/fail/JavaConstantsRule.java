package com.example.rulecore.rules.java.fail;

import com.example.rulecore.rules.ArchUnitBasedRule;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

public class JavaConstantsRule extends ArchUnitBasedRule {

    @Override
    protected ArchRule getDefinition() {
        // public static 필드는 반드시 final이어야 한다. (상수 외의 공유 변수 금지)
        return ArchRuleDefinition.fields()
                .that().arePublic()
                .and().areStatic()
                .should().beFinal()
                .allowEmptyShould(true)
                .as("public static 필드는 final 이어야 한다.");
    }
}
