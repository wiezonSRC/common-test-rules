package com.example.rulecore.report;

import com.example.rulecore.ruleEngine.RuleViolation;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 검사 결과를 JSON 파일 형식으로 외부로 내보내는 역할을 담당하는 클래스입니다.
 */
public class Report {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path outputDir;

    public Report(Path outputDir) {
        this.outputDir = outputDir;
    }

    /**
     * WARN 등급의 위반 사항들을 JSON 파일로 저장합니다.
     */
    public void createWarnReport(List<RuleViolation> warnList){
        if(warnList.isEmpty()){
            return;
        }

        saveReport(warnList, "warn-report.json");
    }

    /**
     * FAIL 등급의 위반 사항들을 JSON 파일로 저장합니다.
     */
    public void createFailReport(List<RuleViolation> failList){
        if (failList.isEmpty()) {
            return;
        }

        saveReport(failList, "fail-report.json");
    }

    private void saveReport(List<RuleViolation> list, String fileName) {
        try {
            if (!Files.exists(outputDir)) {
                Files.createDirectories(outputDir);
            }
            Path path = outputDir.resolve(fileName).toAbsolutePath().normalize();
            Files.write(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(list).getBytes());
            System.out.println("[REPORT] Report written to: " + path.toUri());
        } catch (IOException e){
            System.err.println("[REPORT] Failed to write report: " + e.getMessage());
        }
    }
}