package com.rule.commontest;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.Set;

public class TransactionRuleTests {
    private static final String BASE_PACKAGE = "com.rule"; // 대상 루트 패키지

    @Test
    void transactionalMethod_shouldNotSwallowException() throws Exception {

        for (Class<?> serviceClass : scanServiceClasses()) {

            if (!isDataAccessService(serviceClass)) {
                continue;
            }

            analyzeClassWithAsm(serviceClass);
        }
    }

    /**
     * 1️⃣ @Service 클래스 스캔 (Spring Context ❌)
     */
    private Set<Class<?>> scanServiceClasses() throws ClassNotFoundException {

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);

        scanner.addIncludeFilter(new AnnotationTypeFilter(Service.class));

        Set<Class<?>> classes = new java.util.HashSet<>();

        for (var beanDef : scanner.findCandidateComponents(BASE_PACKAGE)) {
            classes.add(Class.forName(beanDef.getBeanClassName()));
        }
        return classes;
    }

    /**
     * 2️⃣ ASM 분석
     */
    private void analyzeClassWithAsm(Class<?> clazz) throws Exception {

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
                    System.out.println(
                            "WARN: @Transactional swallowed exception → "
                                    + clazz.getName() + "." + method.name
                    );
                }
            }
        }
    }

    /**
     * 3️⃣ @Transactional 여부 (ASM)
     */
    private boolean hasTransactional(MethodNode method) {
        if (method.visibleAnnotations == null) return false;

        return method.visibleAnnotations.stream()
                .anyMatch(a -> a.desc.contains("Transactional"));
    }

    /**
     * 4️⃣ try-catch + return 탐지
     */
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
     * 5️⃣ DataAccess Service 필터 (1차 버전)
     */
    private boolean isDataAccessService(Class<?> clazz) {
        return java.util.Arrays.stream(clazz.getDeclaredFields())
                .anyMatch(f ->
                        f.getType().getName().toLowerCase().contains("mapper")
                );
    }

}
