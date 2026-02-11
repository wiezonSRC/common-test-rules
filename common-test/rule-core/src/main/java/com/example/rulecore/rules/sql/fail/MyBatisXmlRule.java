package com.example.rulecore.rules.sql.fail;

import com.example.rulecore.ruleEngine.Rule;
import com.example.rulecore.ruleEngine.RuleContext;
import com.example.rulecore.ruleEngine.RuleViolation;
import com.example.rulecore.rules.sql.MyBatisIfRuleHandler;
import com.example.rulecore.util.Status;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class MyBatisXmlRule implements Rule {

    @Override
    public List<RuleViolation> check(RuleContext context) throws Exception {
        List<RuleViolation> violations = new ArrayList<>();
        List<Path> mapperDirs = context.mapperDirs();

        if (mapperDirs == null || mapperDirs.isEmpty()) {
            return violations;
        }

        for (Path dir : mapperDirs) {
            if (!Files.exists(dir)) continue;

            try (Stream<Path> files = Files.walk(dir)) {
                files.filter(p -> p.toString().endsWith(".xml"))
                        .forEach(path -> checkXmlFile(path, violations));
            }
        }
        return violations;
    }

    private void checkXmlFile(Path path, List<RuleViolation> violations) {
        File file = path.toFile();
        String fileName = file.getName();

        try {
            // 1. Text-based check for CDATA requirement
            // Requirement: <, > CDATA 필수 -> &lt;, &gt; 사용 지양
            List<String> lines = Files.readAllLines(path);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.contains("&lt;") || line.contains("&gt;")) {
                    violations.add(new RuleViolation(
                            "MyBatisXmlRule",
                            Status.FAIL,
                            "<![CDATA[ ... ]]> 를 사용해라  (* (&lt;, &gt;) 오류 발생 가능성 높음)",
                            file.getAbsolutePath(),
                            i+1
                    ));
                }
            }

            // 2. DOM-based check for <if> scope
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(file);
            doc.getDocumentElement().normalize();

            NodeList ifNodes = doc.getElementsByTagName("if");
            for (int i = 0; i < ifNodes.getLength(); i++) {
                Node ifNode = ifNodes.item(i);
                if (ifNode.getNodeType() == Node.ELEMENT_NODE) {
                    checkIfScope((Element) ifNode, file , violations);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            violations.add(new RuleViolation(
                    "MyBatisXmlRule",
                    Status.FAIL,
                    "XML Parsing Error: " + e.getMessage(),
                    file.getAbsolutePath(),
                    0
            ));
        }
    }

    private void checkIfScope(Element ifElement, File file,  List<RuleViolation> violations) throws ParserConfigurationException, SAXException, IOException {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        SAXParser parser = factory.newSAXParser();

        parser.parse(file, new MyBatisIfRuleHandler(file, violations));

    }
}
