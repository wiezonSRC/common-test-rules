//package com.example.sqlanalyzer.plugin;
//
//import com.example.sqlanalyzer.core.*;
//import org.gradle.api.DefaultTask;
//import org.gradle.api.GradleException;
//import org.gradle.api.tasks.Input;
//import org.gradle.api.tasks.Optional;
//import org.gradle.api.tasks.TaskAction;
//import org.gradle.api.tasks.options.Option;
//
//import java.io.File;
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.util.List;
//import java.util.Scanner;
//
//public class SqlAnalyzerTask extends DefaultTask {
//
//    private SqlAnalyzerExtension extension;
//
//    public void setExtension(SqlAnalyzerExtension extension) {
//        this.extension = extension;
//    }
//
//    private String queryId;
//
//    @Input
//    @Optional
//    @Option(option = "queryId", description = "The MyBatis query ID to analyze")
//    public void setQueryId(String queryId) {
//        this.queryId = queryId;
//    }
//
//    @TaskAction
//    public void run() {
//        String targetQueryId = queryId != null ? queryId : extension.getQueryId().getOrNull();
//        if (targetQueryId == null || targetQueryId.isEmpty()) {
//            throw new GradleException("Query ID is required. Use --queryId or set it in the extension.");
//        }
//
//        getLogger().lifecycle("Analyzing SQL Performance for Query ID: {}", targetQueryId);
//
//        try {
//            // 1. 매퍼 파일 찾기
//            Path projectRoot = getProject().getProjectDir().toPath();
//            List<String> mapperPaths = extension.getMapperPaths().get();
//            Path mapperSearchDir = mapperPaths.isEmpty()
//                ? projectRoot.resolve("src/main/resources/mapper")
//                : projectRoot.resolve(mapperPaths.get(0));
//
//            List<Path> foundFiles = SqlExtractor.findMapperFiles(mapperSearchDir, targetQueryId);
//
//            if (foundFiles.isEmpty()) {
//                throw new GradleException("No mapper files found containing Query ID: " + targetQueryId);
//            }
//
//            Path selectedFile;
//            if (foundFiles.size() == 1) {
//                selectedFile = foundFiles.get(0);
//                getLogger().lifecycle("Found matching query in: {}", selectedFile.getFileName());
//            } else {
//                // 여러 개일 경우 선택 (CLI 입력은 Gradle Task 내에서 복잡할 수 있으므로 리스트 출력 후 가이드)
//                getLogger().lifecycle("Multiple mapper files found:");
//                for (int i = 0; i < foundFiles.size(); i++) {
//                    getLogger().lifecycle("[{}] {}", i + 1, foundFiles.get(i));
//                }
//                throw new GradleException("Multiple mapper files found. Please specify the exact file path in configuration.");
//            }
//
//            // 2. SQL 추출 및 단순화
//            String rawXml = SqlExtractor.extractRawSql(selectedFile, targetQueryId);
//            String simplifiedSql = SqlSimplifier.simplify(rawXml);
//            String finalSql = SqlSimplifier.finalizeSql(simplifiedSql);
//
//            // 3. DB 분석 (EXPLAIN + Schema)
//            String url = extension.getJdbcUrl().get();
//            String user = extension.getJdbcUser().get();
//            String password = extension.getJdbcPassword().get();
//
//            JdbcAnalyzer.AnalysisResult analysis = JdbcAnalyzer.analyze(finalSql, url, user, password);
//
//            // 4. 프롬프트 생성
//            String prompt = PromptGenerator.generate(targetQueryId, rawXml, finalSql, analysis);
//
//            // 5. 결과 파일 저장
//            File reportFile = getProject().getBuildDir().toPath().resolve("reports/sql-ai-prompt.md").toFile();
//            reportFile.getParentFile().mkdirs();
//            Files.writeString(reportFile.toPath(), prompt);
//
//            getLogger().lifecycle("**************************************************");
//            getLogger().lifecycle("Prompt generated successfully!");
//            getLogger().lifecycle("Report File: {}", reportFile.getAbsolutePath());
//            getLogger().lifecycle("Copy the content and paste it into ChatGPT/Gemini.");
//            getLogger().lifecycle("**************************************************");
//
//        } catch (Exception e) {
//            getLogger().error("Error during SQL analysis", e);
//            throw new GradleException("SQL Analysis failed: " + e.getMessage());
//        }
//    }
//}
