package com.example.sqlanalyzer.core;

import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class SqlExtractor {

    public static List<Path> findMapperFiles(Path searchDir, String queryId) {
        List<Path> foundFiles = new ArrayList<>();
        File dir = searchDir.toFile();
        if (!dir.exists() || !dir.isDirectory()) return foundFiles;

        searchRecursive(dir, queryId, foundFiles);
        return foundFiles;
    }

    private static void searchRecursive(File dir, String queryId, List<Path> foundFiles) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                searchRecursive(file, queryId, foundFiles);
            } else if (file.getName().endsWith(".xml")) {
                if (containsQueryId(file, queryId)) {
                    foundFiles.add(file.toPath());
                }
            }
        }
    }

    private static boolean containsQueryId(File xmlFile, String queryId) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(xmlFile);
            
            NodeList nodes = doc.getElementsByTagName("*");
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    Element element = (Element) node;
                    if (queryId.equals(element.getAttribute("id"))) {
                        String tagName = element.getTagName().toLowerCase();
                        return List.of("select", "insert", "update", "delete").contains(tagName);
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static String extractRawSql(Path xmlFile, String queryId) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(xmlFile.toFile());

        NodeList nodes = doc.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                Element element = (Element) node;
                if (queryId.equals(element.getAttribute("id"))) {
                    return nodeToString(element);
                }
            }
        }
        throw new RuntimeException("Query ID '" + queryId + "' not found in " + xmlFile);
    }

    private static String nodeToString(Node node) throws Exception {
        StringWriter sw = new StringWriter();
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.transform(new DOMSource(node), new StreamResult(sw));
        return sw.toString();
    }
}
