package com.example.rulecore.rules.java.fail;

import com.example.rulecore.rules.java.ArchUnitBasedRule;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.TryCatchBlock;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

import java.util.Set;

/**
 * Exception, Throwable, RuntimeException과 같은 일반적인 예외를 직접 catch하는 것을 금지하는 규칙입니다. (1순위)
 */
public class NoGenericCatchRule extends ArchUnitBasedRule {

    private static final Set<String> FORBIDDEN_EXCEPTIONS = Set.of(
            "java.lang.Exception",
            "java.lang.Throwable",
            "java.lang.RuntimeException",
            "java.lang.Error"
    );

    @Override
    protected ArchRule getDefinition() {
        return ArchRuleDefinition.methods()
                .should(notCatchGenericExceptions())
                .as("일반적인 Exception 대신 구체적인 예외를 처리해야 합니다.");
    }

    private ArchCondition<JavaMethod> notCatchGenericExceptions() {
        return new ArchCondition<>("not catch generic exceptions") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                for (TryCatchBlock tryCatchBlock : method.getTryCatchBlocks()) {
                    tryCatchBlock.getCaughtThrowables().forEach(throwable -> {
                        if (FORBIDDEN_EXCEPTIONS.contains(throwable.getName())) {
                            String message = String.format("Method %s catches generic exception %s", 
                                    method.getFullName(), throwable.getName());
                            events.add(SimpleConditionEvent.violated(method, message));
                        }
                    });
                }
            }
        };
    }
}