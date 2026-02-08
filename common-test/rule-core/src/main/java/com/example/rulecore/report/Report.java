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

    private final static String exportWarnPath = "./warn-report.json";
    private final static String exportFailPath = "./fail-report.json";
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * WARN 등급의 위반 사항들을 JSON 파일로 저장합니다.
     * @param warnList 경고 등급 위반 목록
     */
    public void createWarnReport(List<RuleViolation> warnList){
        if(warnList.isEmpty()){
            return;
        }

        try {
            Path path = resolvePath(exportWarnPath);

            Files.write(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(warnList).getBytes());

            System.out.println(
                    "[WARN] " + warnList.size() +
                            " rule violations detected. Report written to: " +
                            path.toAbsolutePath().toUri()
            );
        } catch (IOException e){
            System.err.println("[WARN] Failed to write warn report: " + e.getMessage());
        }

    }

    /**
     * FAIL 등급의 위반 사항들을 JSON 파일로 저장하고,
     * 위반 내역이 존재할 경우 AssertionError를 발생시켜 테스트를 중단시킵니다.
     * 
     * @param failList 실패 등급 위반 목록
     */
    public void createFailReport(List<RuleViolation> failList){

        if (failList.isEmpty()) {
            return;
        }

        Path path = resolvePath(exportFailPath);

        try{
            Files.write(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(failList).getBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        throw new AssertionError("Fail exists : size (" + failList.size() +"). " +
                "\n Please Check " + path.toAbsolutePath().toUri());
    }

    private Path resolvePath(String relativePath) {
        return Path.of(relativePath)
                .toAbsolutePath()
                .normalize();
    }
}
