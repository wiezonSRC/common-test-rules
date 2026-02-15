package com.example.rulecore.rules.java.fail;

import com.example.rulecore.rules.java.ArchUnitBasedRule;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;

/**
 * Transactional 메서드 내에서 예외를 catch하고 다시 throw하지 않는 경우를 ASM으로 정밀 분석합니다.
 */
public class TransactionalSwallowExceptionRule extends ArchUnitBasedRule {

    @Override
    protected ArchRule getDefinition() {
        return ArchRuleDefinition.methods()
                .that().areAnnotatedWith(Transactional.class)
                .or().areDeclaredInClassesThat().areAnnotatedWith(Transactional.class)
                .should(notSwallowException())
                .as("@Transactional 메서드에서는 예외를 반드시 다시 던져야(throw) 합니다.");
    }

    private ArchCondition<JavaMethod> notSwallowException() {
        return new ArchCondition<>("not swallow exception") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                try {
                    String classResource = "/" + method.getOwner().getName().replace(".", "/") + ".class";
                    try (InputStream is = method.getOwner().reflect().getResourceAsStream(classResource)) {
                        if (is == null) return;
                        ClassReader cr = new ClassReader(is);
                        ClassNode cn = new ClassNode();
                        cr.accept(cn, 0);

                        for (MethodNode mn : cn.methods) {
                            if (mn.name.equals(method.getName()) && mn.tryCatchBlocks != null && !mn.tryCatchBlocks.isEmpty()) {
                                if (isSwallowing(mn)) {
                                    events.add(SimpleConditionEvent.violated(method, 
                                        String.format("Method %s catches exception but returns without throwing it back. Transaction rollback will not occur.", method.getFullName())));
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        };
    }

    private boolean isSwallowing(MethodNode mn) {
        for (TryCatchBlockNode tcb : mn.tryCatchBlocks) {
            AbstractInsnNode current = tcb.handler;
            while (current != null) {
                int opcode = current.getOpcode();
                if (opcode == Opcodes.ATHROW) break; // 정상: 다시 던짐
                if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                    return true; // 위반: 다시 던지지 않고 리턴함
                }
                current = current.getNext();
            }
        }
        return false;
    }
}
