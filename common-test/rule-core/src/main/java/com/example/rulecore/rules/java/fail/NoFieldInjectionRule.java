package com.example.rulecore.rules.java.fail;

import com.example.rulecore.rules.ArchUnitBasedRule;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.springframework.beans.factory.annotation.Autowired;

public class NoFieldInjectionRule extends ArchUnitBasedRule {

    @Override
    protected ArchRule getDefinition() {
        return ArchRuleDefinition.noFields()
                .should().beAnnotatedWith(Autowired.class)
                .allowEmptyShould(true)
                .as(" 멤버필드에는 @Autowired 를 사용하지 않는다. ");
    }
}
