package com.example.rulecore.rules.sql.fail;

import com.example.rulecore.ruleEngine.Rule;
import com.example.rulecore.ruleEngine.RuleContext;
import com.example.rulecore.ruleEngine.RuleViolation;
import com.example.rulecore.util.Status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class NoDollarExpressionRule implements Rule {

    @Override
    public List<RuleViolation> check(RuleContext context) {

        List<RuleViolation> violations = new ArrayList<>();

        for (Path mapperDir : context.mapperDirs()) {
            if (!Files.exists(mapperDir)) {
                continue;
            }

            // [수정] try-catch 범위를 조정하고, 이미 위반사항을 찾았다면 스캔 에러는 무시하도록 개선
            try (Stream<Path> files = Files.walk(mapperDir)) {
                files
                        .filter(p -> p.toString().endsWith(".xml"))
                        .forEach(xml -> inspectXml(xml, violations));

            } catch (IOException e) {
                // 이미 찾은 위반 사항이 있다면, 스캔 중단 에러는 굳이 보여주지 않아도 됨 (노이즈 제거)
                // 혹은 로그로만 남기는 것이 좋음
                if (violations.isEmpty()) {
                    violations.add(new RuleViolation(
                            "NoDollarExpressionRule",
                            Status.FAIL,
                            "Failed to scan mapper directory (Check permissions or file locks)",
                            mapperDir.toString(),
                            0
                    ));
                }
            }
        }

        return violations;
    }

    private void inspectXml(Path xml, List<RuleViolation> violations) {
        try {
            List<String> lines =
                    Files.readAllLines(xml, StandardCharsets.UTF_8);

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                int lineNo = i + 1;

                // 주석 라인
                if (line.contains("--") && line.contains("${")) {
                    violations.add(new RuleViolation(
                            "NoDollarExpressionRule",
                            Status.FAIL,
                            "Suspicious ${} usage inside SQL comment",
                            xml.toFile().getAbsolutePath(),
                            lineNo
                    ));
                }
                // 실제 ${} 사용
                else if (line.contains("${")) {
                    violations.add(new RuleViolation(
                            "NoDollarExpressionRule",
                            Status.FAIL,
                            "Found ${} usage in mapper XML (SQL Injection risk)",
                            xml.toFile().getAbsolutePath(),
                            lineNo
                    ));
                }
            }
        } catch (IOException e) {
            violations.add(new RuleViolation(
                    "NoDollarExpressionRule",
                    Status.WARN,
                    "Failed to read mapper XML",
                    xml.toFile().getAbsolutePath(),
                    0
            ));
        }
    }

    private String formatLocation(Path file, int lineNo, String content) {
        // [수정] 출력 포맷을 한 줄로 간결하게 변경 (선택 사항)
        return String.format("[FILE] %s : LINE [ %d ] >> %s", file.getFileName(), lineNo, content.trim());
    }
}
