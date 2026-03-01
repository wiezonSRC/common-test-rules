package com.example.rulecore.report;

import com.example.rulecore.ruleEngine.RuleResult;
import com.example.rulecore.ruleEngine.RuleViolation;
import com.example.rulecore.ruleEngine.enums.Status;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 검사 결과를 JSON 파일 형식으로 외부로 내보내는 역할을 담당하는 클래스입니다.
 */
public class Report {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path outputDir;
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public Report(Path outputDir) {
        this.outputDir = outputDir;
    }

    /**
     * 실행 요약 정보(시간, 위반 개수 등)를 JSON 파일로 저장합니다.
     */
    public void createSummaryReport(RuleResult result) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("executionTimeMillis", result.executionTimeMillis());
        summary.put("totalViolations", result.violations().size());
        summary.put("failCount", result.violations().stream().filter(v -> v.status() == Status.FAIL).count());
        summary.put("warnCount", result.violations().stream().filter(v -> v.status() == Status.WARN).count());
        summary.put("timestamp", LocalDateTime.now().format(formatter));
        summary.put("success", !result.hasError());

        String fileName = "summary-report_" + LocalDateTime.now().format(formatter) + ".json";
        saveReport(summary, fileName);
    }

    /**
     * WARN 등급의 위반 사항들을 JSON 파일로 저장합니다.
     */
    public void createWarnReport(List<RuleViolation> warnList){
        if(warnList.isEmpty()){
            return;
        }

        String fileName = "warn-report_" + LocalDateTime.now().format(formatter) + ".json";
        saveReport(warnList, fileName);
    }

    /**
     * FAIL 등급의 위반 사항들을 JSON 파일로 저장합니다.
     */
    public void createFailReport(List<RuleViolation> failList){
        if (failList.isEmpty()) {
            return;
        }

        String fileName = "fail-report_" + LocalDateTime.now().format(formatter) + ".json";
        saveReport(failList, fileName);
    }

    /**
     * 검사 결과를 콘솔에 출력합니다. (IDE에서 바로가기 링크로 인식 가능한 형식)
     */
    public void printConsoleReport(RuleResult result) {
        if (result.violations().isEmpty()) {
            System.out.println("\n[RULE CHECK] No violations found. Well done!\n");
            return;
        }

        System.out.println("\n========================================");
        System.out.println("          RULE CHECK RESULTS");
        System.out.println("========================================\n");

        for (RuleViolation violation : result.violations()) {
            System.out.println(violation.format());
        }

        System.out.println("========================================");
        System.out.println("Summary:");
        System.out.println("- Total Violations: " + result.violations().size());
        System.out.println("- Fail: " + result.violations().stream().filter(v -> v.status() == Status.FAIL).count());
        System.out.println("- Warn: " + result.violations().stream().filter(v -> v.status() == Status.WARN).count());
        System.out.println("Execution Time: " + result.executionTimeMillis() + "ms");
        System.out.println("========================================\n");
    }

    private void saveReport(Object data, String fileName) {
        try {
            if (!Files.exists(outputDir)) {
                Files.createDirectories(outputDir);
            }
            Path path = outputDir.resolve(fileName).toAbsolutePath().normalize();
            Files.write(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data).getBytes());
            System.out.println("[REPORT] Report written to: " + path.toUri());
        } catch (IOException e){
            System.err.println("[REPORT] Failed to write report: " + e.getMessage());
        }
    }
}
