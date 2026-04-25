# 05. XML 파싱과 JDBC 기초

`XmlQueryIdReader`, `SqlExtractor`, `JdbcAnalyzer`가 사용하는 기술들을 설명한다.

---

## Part 1: Java XML 파싱 (DOM)

### XML 파싱 방식 비교

| 방식 | 특징 | 사용 위치 |
|---|---|---|
| **DOM** | XML 전체를 메모리에 트리로 로드 → 탐색·수정 자유롭지만 메모리 소비 큼 | `XmlQueryIdReader`, `SqlExtractor` |
| SAX | 이벤트 기반 스트리밍 → 메모리 효율적이지만 역방향 탐색 불가 | `rule-core` 모듈에서 사용 |
| StAX | SAX와 유사하나 pull 방식 | — |

우리 코드는 **DOM 방식**을 사용한다. 이유: 파일 전체를 탐색해 특정 태그를 찾아야 하기 때문.

### DOM 파싱 기본 흐름

```java
// 1. 파서 팩토리 생성
DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
factory.setValidating(false);  // DTD 검증 비활성화 (오프라인 환경 지원)
// DTD 검증을 켜면 mybatis-3-mapper.dtd를 인터넷에서 다운로드하려 시도함

// 2. 파서 생성
DocumentBuilder builder = factory.newDocumentBuilder();

// 3. XML 파일을 DOM 트리로 파싱
Document doc = builder.parse(new File("PaymentMapper.xml"));

// 4. DOM 트리 탐색
NodeList selectNodes = doc.getElementsByTagName("select");
for (int i = 0; i < selectNodes.getLength(); i++) {
    Node node = selectNodes.item(i);
    String id = node.getAttributes().getNamedItem("id").getNodeValue();
    System.out.println(id); // "findById", "findAll" 등
}
```

### MyBatis 매퍼 XML의 DOM 구조

```xml
<!-- PaymentMapper.xml -->
<mapper namespace="com.example.mapper.PaymentMapper">
    <select id="findById" resultType="Payment">
        SELECT id, amount FROM payment WHERE id = #{id}
    </select>
    <insert id="save">
        INSERT INTO payment (amount) VALUES (#{amount})
    </insert>
</mapper>
```

이 파일을 DOM으로 파싱하면:

```
Document
└── Element: mapper (rootElement)
    ├── Attribute: namespace = "com.example.mapper.PaymentMapper"
    ├── Element: select
    │   ├── Attribute: id = "findById"
    │   ├── Attribute: resultType = "Payment"
    │   └── TextNode: "SELECT id, amount FROM payment WHERE id = ?"
    └── Element: insert
        ├── Attribute: id = "save"
        └── TextNode: "INSERT INTO payment (amount) VALUES (?)"
```

### `XmlQueryIdReader`가 하는 일

```java
// DML 태그(select, insert, update, delete)의 id 속성을 수집
for (String tag : new String[]{"select", "insert", "update", "delete"}) {
    NodeList nodeList = doc.getElementsByTagName(tag);
    for (int i = 0; i < nodeList.getLength(); i++) {
        Node idAttr = nodeList.item(i).getAttributes().getNamedItem("id");
        if (idAttr != null) {
            ids.add(idAttr.getNodeValue());  // "findById", "save" 등
        }
    }
}
```

→ 드롭다운(`JComboBox`)에 이 id 목록을 채운다.

### XXE(XML External Entity) 공격 차단

```java
factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
factory.setXIncludeAware(false);
factory.setExpandEntityReferences(false);
```

**XXE란?** 악의적인 XML에 외부 파일을 참조하는 엔티티를 삽입해, 서버의 민감 파일(`/etc/passwd` 등)을 읽는 공격.  
플러그인은 외부 XML 파일을 파싱하므로 이 설정이 반드시 필요하다.

---

## Part 2: JDBC 기초

### JDBC란?

JDBC(Java Database Connectivity)는 Java에서 DB에 접속하는 표준 API다.  
드라이버만 바꾸면 MySQL, MariaDB, PostgreSQL 등 어떤 DB든 동일한 코드로 사용할 수 있다.

### 기본 연결 흐름

```java
// 1. DB 연결 (URL, 사용자명, 비밀번호)
Connection connection = DriverManager.getConnection(
    "jdbc:mariadb://localhost:3306/mydb",
    "root",
    "password"
);

// 2. SQL 실행
try (Statement stmt = connection.createStatement();
     ResultSet rs = stmt.executeQuery("EXPLAIN SELECT * FROM payment")) {

    // 3. 결과 읽기
    ResultSetMetaData meta = rs.getMetaData();
    int columnCount = meta.getColumnCount();  // 컬럼 개수

    while (rs.next()) {  // 다음 행으로 이동
        for (int col = 1; col <= columnCount; col++) {
            String value = rs.getString(col);  // 컬럼 인덱스는 1부터 시작
        }
    }
}

// 4. 연결 해제 (try-with-resources로 자동 처리)
connection.close();
```

### `SqlAnalyzerService`의 연결 패턴

```java
// try-with-resources: 블록을 벗어나면 connection.close() 자동 호출
try (Connection connection = DriverManager.getConnection(
        config.getJdbcUrl(), config.getJdbcUser(), config.getJdbcPassword())) {

    return PromptGenerator.generatePrompt(connection, queryId, ...);
}
```

### `DatabaseMetaData` — DB 스키마 정보 조회

```java
DatabaseMetaData meta = connection.getMetaData();

// DB 버전 정보
meta.getDatabaseProductName();     // "MariaDB"
meta.getDatabaseProductVersion();  // "10.11.3-MariaDB"

// 테이블 컬럼 정보
ResultSet rs = meta.getColumns(null, null, "PAYMENT", null);
// → 컬럼명, 데이터 타입, 크기 등을 ResultSet으로 반환

// 인덱스 정보
ResultSet rs = meta.getIndexInfo(null, null, "PAYMENT", false, false);
// → 인덱스명, 컬럼명, 유니크 여부 등을 ResultSet으로 반환
```

SQL을 직접 실행하지 않고 메타데이터 API를 사용하는 이유:  
→ DB 종류에 관계없이 동일한 코드로 스키마 정보를 조회할 수 있기 때문

### EXPLAIN — 실행 계획

```sql
EXPLAIN SELECT p.id, p.amount FROM payment p WHERE p.user_id = ?
```

MySQL/MariaDB에서 `EXPLAIN`은 쿼리를 실제 실행하지 않고 **실행 계획**만 반환한다.

| 컬럼 | 의미 |
|---|---|
| `type` | 조인 방식 (`ALL`=풀스캔, `ref`=인덱스 사용 등) |
| `key` | 실제 사용된 인덱스 이름 |
| `rows` | 예상 검사 행 수 |
| `Extra` | 추가 정보 (`Using filesort`, `Using index` 등) |

`JdbcAnalyzer.getExplainInfo()`는 이 결과를 텍스트로 정리해 AI 프롬프트에 포함시킨다.

---

## Part 3: `java.util.Properties` — 설정 파일 로딩

```java
// .sql-analyzer.properties 파일 예시
// jdbc.url=jdbc:mariadb://localhost:3306/mydb
// jdbc.user=root
// jdbc.password=secret
// mapper.base.dir=src/main/resources/mapper

Properties properties = new Properties();

try (InputStream input = Files.newInputStream(configPath)) {
    properties.load(input);  // key=value 형식의 파일을 Map처럼 로드
}

String jdbcUrl = properties.getProperty("jdbc.url");  // "jdbc:mariadb://..."
```

`SqlAnalyzerConfig.load()`가 이 방식으로 `.sql-analyzer.properties`를 읽는다.

> 다음 문서: [06-code-walkthrough.md](06-code-walkthrough.md) — 전체 코드 흐름 상세 설명
