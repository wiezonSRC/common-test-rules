package com.example.rulecore.ruleEngine;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * 규칙 검사에 필요한 환경 정보를 담고 있는 컨텍스트 클래스입니다.
 */
public record RuleContext(
        String basePackage,
        Path projectRoot,
        List<Path> mapperDirs,
        List<Path> affectedFiles
) {
    public boolean hasAffectedFiles() {
        return affectedFiles != null && !affectedFiles.isEmpty();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String basePackage;
        private Path projectRoot;
        private List<Path> mapperDirs = Collections.emptyList();
        private List<Path> affectedFiles = Collections.emptyList();

        public Builder basePackage(String basePackage) {
            this.basePackage = basePackage;
            return this;
        }

        public Builder projectRoot(Path projectRoot) {
            this.projectRoot = projectRoot;
            return this;
        }

        public Builder mapperDirs(List<Path> mapperDirs) {
            this.mapperDirs = mapperDirs;
            return this;
        }

        public Builder affectedFiles(List<Path> affectedFiles) {
            this.affectedFiles = affectedFiles;
            return this;
        }

        public RuleContext build() {
            return new RuleContext(basePackage, projectRoot, mapperDirs, affectedFiles);
        }
    }
}
