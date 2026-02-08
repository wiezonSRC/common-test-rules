package com.example.rulecore.report;

import com.example.rulecore.ruleEngine.RuleViolation;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Report {

    private final static String exportWarnPath = "./warn-report.json";
    private final static String exportFailPath = "./fail-report.json";
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void createWarnReport(List<RuleViolation> warnList){
        try {
            Path path = Path.of(exportWarnPath);
            Files.write(path, objectMapper.writeValueAsString(warnList).getBytes());
        } catch (IOException e){
            e.printStackTrace();
        }
    }

    public void createFailReport(List<RuleViolation> failList){
        try{
            Path path = Path.of(exportFailPath);
            Files.write(path, objectMapper.writeValueAsString(failList).getBytes());
            throw new AssertionError("Fail exists : size (" + failList.size() +"). \n Please Check fail-report.json");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
