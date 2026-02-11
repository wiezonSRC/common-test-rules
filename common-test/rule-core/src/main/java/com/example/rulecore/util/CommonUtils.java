package com.example.rulecore.util;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;

public class CommonUtils {

    public static Integer findFirstLineNumber(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof LineNumberNode line) {
                return line.line;
            }
        }
        return 1;
    }

    public static String resolveSourcePath(Class<?> clazz) {
        return "src/main/java/" +
                clazz.getName().replace(".", "/") +
                ".java";
    }
}
