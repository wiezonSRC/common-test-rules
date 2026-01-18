package com.example.rulecore.rules;

import com.example.rulecore.Rule;
import com.example.rulecore.RuleContext;
import com.example.rulecore.RuleViolation;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.*;

public class TransactionalSwallowExceptionRule implements Rule {

    @Override
    public List<RuleViolation> check(RuleContext context) throws Exception {

        List<RuleViolation> violations = new ArrayList<>();

        String basePackage = context.getBasePackage();

        for (Class<?> serviceClass : scanServiceClasses(basePackage)) {

            if (!isDataAccessService(serviceClass)) {
                continue;
            }

            analyzeClassWithAsm(serviceClass, violations);
        }

        return violations;
    }

    /**
     * @Service 클래스 스캔 (Context 기반)
     */
    private Set<Class<?>> scanServiceClasses(String basePackage)
            throws ClassNotFoundException {

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);

        scanner.addIncludeFilter(new AnnotationTypeFilter(Service.class));

        Set<Class<?>> classes = new HashSet<>();

        for (var beanDef : scanner.findCandidateComponents(basePackage)) {
            classes.add(Class.forName(beanDef.getBeanClassName()));
        }

        return classes;
    }

    /**
     * ASM 분석
     */
    private void analyzeClassWithAsm(
            Class<?> clazz,
            List<RuleViolation> violations
    ) throws Exception {

        String classFile = "/" + clazz.getName().replace(".", "/") + ".class";

        boolean classTransactional =
                clazz.isAnnotationPresent(Transactional.class);

        try (InputStream is = clazz.getResourceAsStream(classFile)) {
            if (is == null) return;

            ClassReader reader = new ClassReader(is);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, 0);

            for (MethodNode method : classNode.methods) {

                boolean methodTransactional = hasTransactional(method);

                if (!classTransactional && !methodTransactional) {
                    continue;
                }

                if (hasCatchAndReturn(method)) {
                    violations.add(new RuleViolation(
                            "TransactionalSwallowExceptionRule",
                            "@Transactional method swallows exception with catch + return",
                            clazz.getName() + "." + method.name
                    ));
                }
            }
        }
    }

    private boolean hasTransactional(MethodNode method) {
        if (method.visibleAnnotations == null) return false;

        return method.visibleAnnotations.stream()
                .anyMatch(a -> a.desc.contains("Transactional"));
    }

    private boolean hasCatchAndReturn(MethodNode method) {

        if (method.tryCatchBlocks == null || method.tryCatchBlocks.isEmpty()) {
            return false;
        }

        for (AbstractInsnNode insn : method.instructions) {
            int opcode = insn.getOpcode();
            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                return true;
            }
        }
        return false;
    }

    /**
     * DataAccess Service 필터
     */
    private boolean isDataAccessService(Class<?> clazz) {
        return Arrays.stream(clazz.getDeclaredFields())
                .anyMatch(f ->
                        f.getType().getName().toLowerCase().contains("mapper")
                );
    }
}
