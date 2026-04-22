package com.example.sqlanalyzer.intellij.config;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

class SqlAnalyzerConfigTest {

    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("sql-analyzer-config-test");
    }

    @AfterEach
    void tearDown() throws IOException {
        // 임시 디렉토리 정리 (역순 정렬로 하위 파일부터 삭제)
        Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> p.toFile().delete());
    }

    @Test
    @DisplayName("정상적인 properties 파일 로드 - 4개 필수 키 모두 존재")
    void load_success() throws IOException {
        Path configFile = tempDir.resolve(".sql-analyzer.properties");
        Files.writeString(configFile,
                "jdbc.url=jdbc:h2:mem:test\n" +
                "jdbc.user=sa\n" +
                "jdbc.password=\n" +
                "mapper.base.dir=src/main/resources/mapper\n"
        );

        SqlAnalyzerConfig config = SqlAnalyzerConfig.load(configFile);

        assertEquals("jdbc:h2:mem:test", config.getJdbcUrl());
        assertEquals("sa", config.getJdbcUser());
        assertEquals("", config.getJdbcPassword());
        assertEquals("src/main/resources/mapper", config.getMapperBaseDir());
    }

    @Test
    @DisplayName("필수 키 누락 시 IllegalStateException 발생")
    void load_missingRequiredKey_throwsIllegalStateException() throws IOException {
        Path configFile = tempDir.resolve(".sql-analyzer.properties");
        // jdbc.password 와 mapper.base.dir 키 자체가 없는 경우
        Files.writeString(configFile,
                "jdbc.url=jdbc:h2:mem:test\n" +
                "jdbc.user=sa\n"
        );

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> SqlAnalyzerConfig.load(configFile)
        );
        assertTrue(ex.getMessage().contains(".sql-analyzer.properties"));
    }

    @Test
    @DisplayName("파일이 존재하지 않을 때 IOException 발생")
    void load_fileNotFound_throwsIOException() {
        Path nonExistent = tempDir.resolve("nonexistent.properties");
        assertThrows(IOException.class, () -> SqlAnalyzerConfig.load(nonExistent));
    }
}
