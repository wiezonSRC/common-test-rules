package com.example.rulecore.rules.java.fail;

import com.example.rulecore.rules.ArchUnitBasedRule;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.slf4j.LoggerFactory;

public class LombokUsageRule extends ArchUnitBasedRule {

    @Override
    protected ArchRule getDefinition() {
        return ArchRuleDefinition.noClasses()
                .should().callMethod(LoggerFactory.class, "getLogger", Class.class)
                .orShould().callMethod(LoggerFactory.class, "getLogger", String.class)
                .as("Avoid direct usage of LoggerFactory.getLogger(). Use Lombok @Slf4j annotation instead.");
    }
}
