package com.example.rulecore.rules.sql.fail;

import com.example.rulecore.ruleEngine.Rule;
import com.example.rulecore.ruleEngine.RuleContext;
import com.example.rulecore.ruleEngine.RuleViolation;
import com.example.rulecore.util.Status;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
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
                            "Use <![CDATA[ ... ]]> instead of escaped entities (&lt;, &gt;) for readability.",
                            fileName + ":" + (i + 1)
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
                    checkIfScope((Element) ifNode, fileName, violations);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            violations.add(new RuleViolation(
                    "MyBatisXmlRule",
                    Status.FAIL,
                    "XML Parsing Error: " + e.getMessage(),
                    fileName
            ));
        }
    }

    private void checkIfScope(Element ifElement, String fileName, List<RuleViolation> violations) {
        Node parent = ifElement.getParentNode();
        boolean validParent = false;

        while (parent != null) {
            if (parent.getNodeType() == Node.ELEMENT_NODE) {
                String tagName = parent.getNodeName();
                // <if>는 <where>, <set>, <trim>, <foreach> 내부, 혹은 다른 <if>, <choose> 내부에 있어야 안전
                if (tagName.equals("where") || tagName.equals("set") ||
                    tagName.equals("trim") || tagName.equals("foreach") ||
                    tagName.equals("if") || tagName.equals("when") || tagName.equals("otherwise")) {
                    validParent = true;
                    break;
                }
                // <select>, <update> 등을 만나면 루프 종료 (invalid)
                if (tagName.equals("select") || tagName.equals("insert") ||
                    tagName.equals("update") || tagName.equals("delete")) {
                    break;
                }
            }
            parent = parent.getParentNode();
        }

        if (!validParent) {
            violations.add(new RuleViolation(
                    "MyBatisXmlRule",
                    Status.FAIL,
                    "<if> tag should be wrapped in <where>, <set>, <trim> or <foreach> to prevent SQL syntax errors.",
                    fileName + " (Tag: " + ifElement.getAttribute("test") + ")"
            ));
        }
    }
}
