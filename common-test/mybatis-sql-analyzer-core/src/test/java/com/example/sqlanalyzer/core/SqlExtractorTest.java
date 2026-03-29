package com.example.sqlanalyzer.core;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.junit.jupiter.api.*;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SqlExtractorTest {

    private Connection connection;
    private final String queryId = "findBadPerformancePayments";
    private final String mapperPath = "src/test/resources/mapper/TestMapper.xml";
    private final Path mapperBaseDir = Path.of("src/test/resources/mapper");

    @BeforeEach
    void set() throws Exception {
        // H2 메모리 DB 설정 (테이블 및 인덱스 생성)
        connection = DriverManager.getConnection("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1", "sa", "");

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE payments (payment_id VARCHAR(50) PRIMARY KEY, order_id VARCHAR(50) NOT NULL, amount DECIMAL(10, 2) NOT NULL, status VARCHAR(20) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            stmt.execute("CREATE TABLE orders (order_id VARCHAR(50) PRIMARY KEY, user_id VARCHAR(50) NOT NULL, mid VARCHAR(10) NOT NULL)");
            stmt.execute("CREATE TABLE merchants (mid VARCHAR(10) PRIMARY KEY, merchant_name VARCHAR(100) NOT NULL, status VARCHAR(20) DEFAULT 'ACTIVE', created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            stmt.execute("CREATE TABLE refunds (refund_id VARCHAR(50) PRIMARY KEY, payment_id VARCHAR(50) NOT NULL, refund_amount DECIMAL(10, 2) NOT NULL, reason VARCHAR(255), refunded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            
            stmt.execute("CREATE INDEX payments_amount_idx ON payments(amount)");
            stmt.execute("CREATE INDEX orders_user_id_idx ON orders(user_id)");
            stmt.execute("CREATE INDEX idx_merchants_status ON merchants(status)");
            stmt.execute("CREATE INDEX idx_refunds_payment_id ON refunds(payment_id)");
        }
    }

    @AfterEach
    void remove() throws Exception {
        if (connection != null && !connection.isClosed()) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("DROP ALL OBJECTS");
            }
            connection.close();
        }
    }

    @Test
    @DisplayName("QueryId 가 포함된 mapper 찾기")
    void findMapperXml() throws IOException, ParserConfigurationException, SAXException {

        List<String> matchedFilePaths = new ArrayList<>();

        // 1. 매퍼 디렉토리 존재 확인
        if(!Files.isDirectory(mapperBaseDir)){
            Assertions.fail("매퍼 디렉토리가 존재하지 않습니다.");
        }

        // 2. xml 파일 리스트 찾기
        List<File> xmlFileList = Files.walk(mapperBaseDir)
                .filter(path -> Files.isRegularFile(path))
                .filter(path -> path.toString().endsWith("xml"))
                .map(path -> path.toFile())
                .toList();


        // 3. queryId 존재하는 mapper 필터링

        // - XML 파서 셋팅 (인터넷 접속 방지용 DTD 검증 끄기)
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setValidating(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder builder = factory.newDocumentBuilder();

        String[] targetTags = {"select", "insert", "update", "delete"};

        // queryId 매칭해보기
        boolean isMatched = false;
        for(File xml : xmlFileList){
            Document document = builder.parse(xml);
            document.getDocumentElement().normalize();

            for(String tagName : targetTags){
                NodeList nodeList = document.getElementsByTagName(tagName);

                for(int i = 0; i < nodeList.getLength(); i++){
                    if(queryId.equals(nodeList.item(i).getAttributes().getNamedItem("id").getNodeValue())){
                        matchedFilePaths.add(xml.getAbsolutePath());
                        isMatched = true;
                        break;
                    }
                }

                if(isMatched){
                    break;
                }
            }
        }


        Assertions.assertEquals(4, matchedFilePaths.size());
    }

    @Test
    @DisplayName("Dynamic Query 추출")
    void findDynamicQuery() throws Exception {
        Node queryIdNode = getQueryIdDetail(queryId, mapperPath);
        assertNotNull(queryIdNode.getTextContent());
    }

    // 핵심 core 로직

    @Test
    @DisplayName("동적 쿼리 >> SQL 변경")
    void dynamicQueryChangeToSql() throws Exception {
        Node nodeList = getQueryIdDetail(queryId, mapperPath);
        // [mybatis 태그 확인]
        //expalin용 fakeSql
        String fakeSql = buildFakeSql(nodeList, true);

        // 3. When: JSqlParser를 이용해 파싱 및 테이블명 추출
        net.sf.jsqlparser.statement.Statement statement = CCJSqlParserUtil.parse(fakeSql);
        TablesNamesFinder tablesNamesFinder = new TablesNamesFinder();
        Set<String> tableNames = new java.util.HashSet<>(tablesNamesFinder.getTableList(statement));

        // 4. Then: 최종 결과 검증
        System.out.println("생성된 가짜 SQL: " + fakeSql.replaceAll("\\s+", " ").trim());
        System.out.println("추출된 테이블명: " + tableNames);

        Assertions.assertEquals(3, tableNames.size());

    }

    public static String buildFakeSql(Node nodeList, boolean isForExplain) {
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
                        fakeSql.append(" WHERE 1=1 AND ( ");
                        fakeSql.append(buildFakeSql(child, isForExplain));
                        fakeSql.append(" ) ");
                        break;
                    case "set":
                        fakeSql.append(" SET ");
                        String setContent = buildFakeSql(child, isForExplain).trim();
                        if (setContent.endsWith(",")) {
                            setContent = setContent.substring(0, setContent.length() - 1);
                        }
                        fakeSql.append(setContent).append(" ");
                        break;
                    case "foreach":
                        // JSqlParser가 IN (?) 형태로 인식할 수 있도록 더미 처리
                        fakeSql.append(" ( ? ) ");
                        break;
                    case "if":
                        // EXPLAIN을 위해 if 문 안의 내용은 일단 포함시킴 (최대한의 조건 검사)
                        fakeSql.append(" ").append(buildFakeSql(child, isForExplain)).append(" ");
                        break;
                    case "choose":
                        // choose 내부의 when/otherwise 중 하나를 선택하거나 전체를 합침
                        if (isForExplain) {
                            // EXPLAIN 모드일 때는 첫 번째 valid한 경로만 찾거나 otherwise를 찾음
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
                                fakeSql.append(" ").append(buildFakeSql(targetNode, true)).append(" ");
                            }
                        } else {
                            // 테이블 추출 모드일 때는 모든 경로를 합쳐서 테이블명을 다 찾게 함
                            fakeSql.append(" ").append(buildFakeSql(child, false)).append(" ");
                        }
                        break;
                    case "when":
                    case "otherwise":
                    case "trim":
                        fakeSql.append(" ").append(buildFakeSql(child, isForExplain)).append(" ");
                        break;
                    case "bind":
                        // bind 태그는 SQL 결과에 직접적인 영향을 주지 않으므로 무시
                        break;
                    case "include":
                        fakeSql.append(" /* included_sql_placeholder */ ");
                        break;
                    default:
                        // 정의되지 않은 태그도 재귀적으로 내부 텍스트 추출 시도
                        fakeSql.append(buildFakeSql(child, isForExplain));
                        break;
                }
            }
        }

        return fakeSql.toString();
    }


    public static Node getQueryIdDetail(String queryId, String mapperPath) throws ParserConfigurationException, SAXException, IOException {


        // document xml parser dfd 검증로직 종료
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setValidating(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        Document document = builder.parse(mapperPath);

        String[] targetTags = {"select", "delete", "update", "insert"};
        for(String targetTag : targetTags){
            NodeList nodeList = document.getElementsByTagName(targetTag);

            for(int i = 0; i < nodeList.getLength(); i++){
                if(queryId.equals(nodeList.item(i).getAttributes().getNamedItem("id").getNodeValue())){
                    return nodeList.item(i);
                }
            }

        }

        return null;
    }
}
