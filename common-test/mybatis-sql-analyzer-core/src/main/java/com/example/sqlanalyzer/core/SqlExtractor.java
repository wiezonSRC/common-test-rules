package com.example.sqlanalyzer.core;

import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
public class SqlExtractor {

    private SqlExtractor() {}

    static Map<String, String> sqlSnippetRegistry;

    // 가짜 sql 생성
    public static String buildFakeSql(Node nodeList, boolean isForExplain, String currentNamespace, Map<String, String> sqlSnippetRegistry) {
        NodeList childNodes = nodeList.getChildNodes();
        StringBuilder fakeSql = new StringBuilder();

        for (int i = 0; i < childNodes.getLength(); i++) {
            Node child = childNodes.item(i);

            // 1. 텍스트 노드 및 CDATA 처리
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                String text = child.getNodeValue();
                if (text != null) {
                    // #{} 와 ${} 를 ? 로 치환
                    fakeSql.append(text.replaceAll("[#$]\\{[^}]+\\}", "?")).append(" ");
                }
            }
            // 2. 엘리먼트 노드(MyBatis 태그) 처리
            else if (child.getNodeType() == Node.ELEMENT_NODE) {
                String nodeName = child.getNodeName().toLowerCase();
                switch (nodeName) {
                    case "where":
                        fakeSql.append(" WHERE 1=1 ");
                        String whereContent = buildFakeSql(child, isForExplain, currentNamespace, sqlSnippetRegistry);
                        // 앞부분의 AND나 OR를 제거 (JSqlParser 에러 방지용)
                        String cleanedWhere = whereContent.trim().replaceAll("(?i)^(and|or)\\s+", "");
                        if (!cleanedWhere.isEmpty()) {
                            fakeSql.append(" AND ( ").append(cleanedWhere).append(" ) ");
                        }
                        break;
                    case "set":
                        fakeSql.append(" SET ");
                        String setContent = buildFakeSql(child, isForExplain, currentNamespace, sqlSnippetRegistry).trim();
                        if (setContent.endsWith(",")) {
                            setContent = setContent.substring(0, setContent.length() - 1);
                        }
                        fakeSql.append(setContent).append(" ");
                        break;
                    case "foreach":
                        fakeSql.append(" ( ? ) ");
                        break;
                    case "choose":
                        if (isForExplain) {
                            Node targetNode = null;
                            NodeList chooseChildren = child.getChildNodes();
                            for (int j = 0; j < chooseChildren.getLength(); j++) {
                                Node c = chooseChildren.item(j);
                                if (c.getNodeType() == Node.ELEMENT_NODE) {
                                    if ("when".equalsIgnoreCase(c.getNodeName())) {
                                        if (targetNode == null) targetNode = c;
                                    } else if ("otherwise".equalsIgnoreCase(c.getNodeName())) {
                                        targetNode = c;
                                        break;
                                    }
                                }
                            }
                            if (targetNode != null) {
                                fakeSql.append(" ").append(buildFakeSql(targetNode, true, currentNamespace, sqlSnippetRegistry)).append(" ");
                            }
                        } else {
                            fakeSql.append(" ").append(buildFakeSql(child, false, currentNamespace, sqlSnippetRegistry)).append(" ");
                        }
                        break;
                    case "if", "when", "otherwise":
                        fakeSql.append(" ").append(buildFakeSql(child, isForExplain, currentNamespace, sqlSnippetRegistry)).append(" ");
                        break;
                    case "trim":
                        Element trimElem = (Element) child;
                        String prefix = trimElem.getAttribute("prefix");
                        String prefixOverrides = trimElem.getAttribute("prefixOverrides");

                        String trimContent = buildFakeSql(child, isForExplain, currentNamespace, sqlSnippetRegistry).trim();
                        if (!prefixOverrides.isEmpty()) {
                            String[] overrides = prefixOverrides.split("\\|");
                            for (String ov : overrides) {
                                trimContent = trimContent.replaceAll("(?i)^" + Pattern.quote(ov.trim()) + "\\s+", "");
                            }
                        }
                        fakeSql.append(" ").append(prefix).append(" ").append(trimContent).append(" ");
                        break;
                    case "bind":
                        break;
                    case "include":
                        String refid = ((Element) child).getAttribute("refid");
                        String fqn = refid.contains(".") ? refid : currentNamespace + "." + refid;

                        String rawSqlXml = sqlSnippetRegistry.get(fqn);
                        if (rawSqlXml != null) {
                            try {
                                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                                dbf.setValidating(false);
                                dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

                                DocumentBuilder db = dbf.newDocumentBuilder();
                                Document parse = db.parse(new ByteArrayInputStream(rawSqlXml.getBytes()));

                                // <sql> 태그의 내용을 다시 buildFakeSql로 처리 (isForExplain 유지)
                                fakeSql.append(" ").append(buildFakeSql(parse.getDocumentElement(), isForExplain, currentNamespace, sqlSnippetRegistry)).append(" ");
                            } catch (Exception e) {
                                fakeSql.append(" /* ERROR_PARSING_INCLUDE: ").append(fqn).append(" */ ");
                            }
                        } else {
                            fakeSql.append(" /* MISSING_INCLUDE: ").append(fqn).append(" */ ");
                        }
                        break;
                    default:
                        fakeSql.append(buildFakeSql(child, isForExplain, currentNamespace, sqlSnippetRegistry));
                        break;
                }
            }
        }

        return fakeSql.toString();
    }

    // queryId와 매칭되는 태그블럭 return
    public static Node getQueryIdDetail(String queryId, String mapperPath) throws ParserConfigurationException, SAXException, IOException {


        // document xml parser dfd 검증로직 종료
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setValidating(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        Document document = builder.parse(mapperPath);

        String[] targetTags = {"select", "delete", "update", "insert"};
        for (String targetTag : targetTags) {
            NodeList nodeList = document.getElementsByTagName(targetTag);

            for (int i = 0; i < nodeList.getLength(); i++) {
                if (queryId.equals(nodeList.item(i).getAttributes().getNamedItem("id").getNodeValue())) {
                    return nodeList.item(i);
                }
            }

        }

        return null;
    }

    // <sql> 태그 캐싱처리
    public static Map<String, String> getSqlSnippetRegistry(Path mapperBaseDir) throws IOException, ParserConfigurationException, SAXException, TransformerException {
        Map<String, String> sqlSnippetRegistry = new HashMap<>();
        // 1. 매퍼 디렉토리 존재 확인
        if (!Files.isDirectory(mapperBaseDir)) {
            log.info("매퍼 디렉토리가 존재하지 않습니다.");
        }

        // 2. xml 파일 리스트 찾기
        List<File> xmlFileList;
        try (Stream<Path> paths = Files.walk(mapperBaseDir)) {
            xmlFileList = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith("xml"))
                    .map(Path::toFile)
                    .toList();
        }

        // 3. <sql> 찾기
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setValidating(false);
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder builder = dbf.newDocumentBuilder();
        for (File xmlFile : xmlFileList) {
            Document doc = builder.parse(xmlFile);
            doc.getDocumentElement().normalize();

            //[핵심 1] 루트 태그(<mapper>)에서 namespace 속성 추출
            Element mapperElement = doc.getDocumentElement();
            String namespace = mapperElement.getAttribute("namespace");

            // namespace가 없는 xml(예: 설정파일 등)은 스킵
            if (namespace.trim().isEmpty()) continue;

            // <sql> 태그 찾기
            NodeList sqlNodes = doc.getElementsByTagName("sql");
            for (int i = 0; i < sqlNodes.getLength(); i++) {
                Element sqlElement = (Element) sqlNodes.item(i);

                //[핵심 2] sql 태그의 id 추출하여 풀네임(FQN) Key 생성
                String id = sqlElement.getAttribute("id");
                String fqn = namespace + "." + id;

                //[핵심 3] 내부 태그 유지를 위해 Node를 순수 XML 문자열로 변환 (메모리 효율을 위해 String 저장)
                TransformerFactory transformerFactory = TransformerFactory.newInstance();
                Transformer transformer = transformerFactory.newTransformer();
                transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");

                StringWriter stringWriter = new StringWriter();
                transformer.transform(new DOMSource(sqlElement), new StreamResult(stringWriter));

                String rawSqlXml = stringWriter.toString();

                sqlSnippetRegistry.put(fqn, rawSqlXml);
            }

        }
        return sqlSnippetRegistry;
    }


    public static String nodeToString(Node node) {
        StringWriter stringWriter = new StringWriter();
        try{
            Transformer transformer = TransformerFactory.newInstance().newTransformer();

            //XML 선언부 제외
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");

            //출력시 정렬
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");

            transformer.transform(new DOMSource(node), new StreamResult(stringWriter));
        }catch(TransformerException e){
            log.error(e.getMessage(), e);
        }
        return stringWriter.toString();
    }
}
