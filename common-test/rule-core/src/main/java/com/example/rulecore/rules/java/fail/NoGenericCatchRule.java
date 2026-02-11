package com.example.rulecore.rules.java.fail;

import com.example.rulecore.ruleEngine.Rule;
import com.example.rulecore.ruleEngine.RuleContext;
import com.example.rulecore.ruleEngine.RuleViolation;
import com.example.rulecore.util.Status;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Service;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.example.rulecore.util.CommonUtils.findFirstLineNumber;
import static com.example.rulecore.util.CommonUtils.resolveSourcePath;

public class NoGenericCatchRule implements Rule {

    private static final Set<String> FORBIDDEN_EXCEPTIONS = Set.of(
            "java/lang/Exception",
            "java/lang/Throwable",
            "java/lang/RuntimeException",
            "java/lang/Error"
    );

    @Override
    public List<RuleViolation> check(RuleContext context) throws Exception {
        List<RuleViolation> violations = new ArrayList<>();
        String basePackage = context.basePackage();

        for (Class<?> clazz : scanClasses(basePackage)) {
            analyzeClassWithAsm(clazz, violations);
        }
        return violations;
    }

    private Set<Class<?>> scanClasses(String basePackage) {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Service.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        // Add more filters if needed (Controller, etc.)

        Set<Class<?>> classes = new HashSet<>();
        try {
            for (var beanDef : scanner.findCandidateComponents(basePackage)) {
                classes.add(Class.forName(beanDef.getBeanClassName()));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return classes;
    }

    private void analyzeClassWithAsm(Class<?> clazz, List<RuleViolation> violations) throws Exception {
        String classFile = "/" + clazz.getName().replace(".", "/") + ".class";
        try (InputStream is = clazz.getResourceAsStream(classFile)) {
            if (is == null) return;

            ClassReader reader = new ClassReader(is);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, 0);

            for (MethodNode method : classNode.methods) {
                for (TryCatchBlockNode tryCatch : method.tryCatchBlocks) {
                    if (tryCatch.type != null && FORBIDDEN_EXCEPTIONS.contains(tryCatch.type)) {
                        violations.add(new RuleViolation(
                                "NoGenericCatchRule",
                                Status.FAIL,
                                "일반적인 Exception 대신해서 특정 예외를 처리. ( 일반적인 예외 : " + tryCatch.type.replace("/", ".") + ")",
                                resolveSourcePath(clazz),
                                findFirstLineNumber(method)
                        ));
                    }
                }
            }
        }
    }
}
