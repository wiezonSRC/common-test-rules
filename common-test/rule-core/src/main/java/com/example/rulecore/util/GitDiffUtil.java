package com.example.rulecore.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Git 명령어를 사용하여 변경된 파일 목록을 추출하는 유틸리티입니다.
 */
public class GitDiffUtil {

    /**
     * HEAD를 기준으로 변경된(staged, unstaged, untracked) 파일 목록을 가져옵니다.
     *
     * @param projectRoot 프로젝트 루트 경로
     * @return 변경된 파일들의 절대 경로 목록
     */
    public static List<Path> getAffectedFiles(Path projectRoot) {
        List<String> relativePaths = new ArrayList<>();

        try {
            // 1. 변경된 파일 (Modified, Added) 추출
            // --name-only: 파일명만, HEAD: 마지막 커밋 대비 모든 변경사항
            relativePaths.addAll(executeCommand(projectRoot, "git", "diff", "--name-only", "HEAD"));

            // 2. Untracked 파일 추출
            relativePaths.addAll(executeCommand(projectRoot, "git", "ls-files", "--others", "--exclude-standard"));

        } catch (Exception e) {
            System.err.println("[GIT] Failed to get diff: " + e.getMessage());
        }

        return relativePaths.stream()
                .distinct()
                .map(projectRoot::resolve)
                .collect(Collectors.toList());
    }

    private static List<String> executeCommand(Path workingDir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workingDir.toFile())
                .start();

        List<String> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    result.add(line.trim());
                }
            }
        }
        process.waitFor();
        return result;
    }
}
