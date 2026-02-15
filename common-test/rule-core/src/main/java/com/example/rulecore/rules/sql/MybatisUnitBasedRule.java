package com.example.rulecore.rules.sql;

import com.example.rulecore.ruleEngine.Rule;
import com.example.rulecore.ruleEngine.RuleContext;
import com.example.rulecore.ruleEngine.RuleViolation;
import org.xml.sax.Locator;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * SAX(Simple API for XML) 기반의 MyBatis 규칙 기명 클래스입니다.
 * 메모리 효율성을 위해 파일을 한 줄씩 읽으며 이벤트를 처리합니다.
 */
public abstract class MybatisUnitBasedRule extends DefaultHandler implements Rule {

    protected Path currentXmlPath;
    protected List<RuleViolation> currentViolations;
    protected Locator locator;

    @Override
    public void setDocumentLocator(Locator locator) {
        this.locator = locator;
    }

    @Override
    public List<RuleViolation> check(RuleContext context) throws Exception {
        List<RuleViolation> allViolations = new ArrayList<>();
        this.currentViolations = allViolations;

        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        SAXParser saxParser = factory.newSAXParser();

        if (context.hasAffectedFiles()) {
            context.affectedFiles().stream()
                    .filter(p -> p.toString().endsWith(".xml"))
                    .forEach(xml -> parseXml(xml, saxParser));
        } else {
            for (Path mapperDir : context.mapperDirs()) {
                if (!Files.exists(mapperDir)) continue;
                try (Stream<Path> files = Files.walk(mapperDir)) {
                    files.filter(p -> p.toString().endsWith(".xml"))
                         .forEach(xml -> parseXml(xml, saxParser));
                } catch (IOException ignored) {}
            }
        }
        return allViolations;
    }

    private void parseXml(Path xml, SAXParser saxParser) {
        this.currentXmlPath = xml;
        try {
            saxParser.parse(xml.toFile(), this);
        } catch (Exception ignored) {
            // 개별 파일 파싱 에러가 전체 검사를 멈추지 않도록 함
        }
    }

    protected int getLineNumber() {
        return locator != null ? locator.getLineNumber() : 0;
    }
}
