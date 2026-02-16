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
     * 특정 기준(baseRef) 대비 변경된 파일 목록을 가져옵니다.
     *
     * @param projectRoot 프로젝트 루트 경로
     * @param baseRef Git 참조 (예: HEAD, main, feature-branch)
     * @return 변경된 파일들의 절대 경로 목록
     */
    public static List<Path> getAffectedFiles(Path projectRoot, String baseRef) {
        List<String> relativePaths = new ArrayList<>();

        try {
            // Git Root 찾기
            String gitRootStr = executeCommand(projectRoot, "git", "rev-parse", "--show-toplevel").get(0);
            Path gitRoot = Path.of(gitRootStr);
            
            // baseRef가 비어있으면 HEAD 사용
            String targetRef = (baseRef == null || baseRef.isBlank()) ? "HEAD" : baseRef;
            
            // 1. 변경된 파일 (Modified, Added) 추출
            // git diff는 git root 기준의 상대경로를 반환함
            List<String> diffFiles = executeCommand(projectRoot, "git", "diff", "--name-only", targetRef);
            for (String file : diffFiles) {
                Path absolutePath = gitRoot.resolve(file);
                // 현재 프로젝트 루트 하위의 파일만 포함
                if (absolutePath.startsWith(projectRoot.toAbsolutePath())) {
                    relativePaths.add(projectRoot.toAbsolutePath().relativize(absolutePath).toString());
                }
            }

            // 2. Untracked 파일 추출
            List<String> untrackedFiles = executeCommand(projectRoot, "git", "ls-files", "--others", "--exclude-standard");
            for (String file : untrackedFiles) {
                Path absolutePath = gitRoot.resolve(file);
                if (absolutePath.startsWith(projectRoot.toAbsolutePath())) {
                    relativePaths.add(projectRoot.toAbsolutePath().relativize(absolutePath).toString());
                }
            }

        } catch (Exception e) {
            System.err.println("[GIT] Failed to get diff for " + baseRef + ": " + e.getMessage());
        }

        return relativePaths.stream()
                .distinct()
                .map(projectRoot::resolve)
                .collect(Collectors.toList());
    }

    /**
     * 기존 하위 호환성을 위한 메서드 (HEAD 기준)
     */
    public static List<Path> getAffectedFiles(Path projectRoot) {
        return getAffectedFiles(projectRoot, "HEAD");
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
