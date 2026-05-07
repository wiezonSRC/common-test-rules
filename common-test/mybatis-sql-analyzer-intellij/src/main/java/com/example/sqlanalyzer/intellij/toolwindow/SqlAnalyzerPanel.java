package com.example.sqlanalyzer.intellij.toolwindow;

import com.example.sqlanalyzer.intellij.config.SqlAnalyzerConfig;
import com.example.sqlanalyzer.intellij.service.SqlAnalyzerService;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.nio.file.Path;
import java.util.List;

/**
 * MyBatis SQL Analyzer Tool Window의 메인 UI 패널.
 *
 * <p>레이아웃 구조:
 * <pre>
 * ┌─ MyBatis SQL Analyzer ──────────────────────────────────┐
 * │  [DB Settings]                                           │  ← DB 설정 버튼
 * ├─────────────────────────────────────────────────────────┤
 * │  Mapper Dir : [경로 입력창              ] [...]          │
 * │  Mapper File: [상대경로/파일명 드롭다운  ]                │  ← 재귀 탐색, 상대경로 표시
 * │  Query ID   : [쿼리ID 드롭다운         ]                 │
 * │                                    [Analyze]              │
 * ├─────────────────────────────────────────────────────────┤
 * │  (결과 텍스트 영역)                                       │
 * ├─────────────────────────────────────────────────────────┤
 * │                         [Copy to Clipboard]              │
 * └─────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>캐스케이드 로직:
 * <ul>
 *   <li>browse 버튼(또는 mapperDirField Enter/포커스 이탈) → {@code reloadMapperFiles()}
 *       — mapper base dir 하위 XML 파일을 재귀 탐색, 상대 경로로 표시</li>
 *   <li>mapperFileCombo 선택 변경 → {@code reloadQueryIds()}
 *       — 선택된 XML 파일의 DML id 목록을 queryIdCombo에 로드</li>
 * </ul>
 *
 * <p>서브디렉토리 depth 제한 없음:
 * {@code payment/approval/ApprovalMapper.xml} 처럼 깊이 중첩된 구조도
 * mapper base dir 기준 상대 경로로 단일 목록에 표시한다.
 *
 * <p>IntelliJ EDT 규칙:
 * UI 갱신은 EDT, DB EXPLAIN 등 I/O 작업은 ProgressManager 백그라운드 스레드에서 실행한다.
 */
@Slf4j
public class SqlAnalyzerPanel extends JPanel {

    private final Project           project;
    private final SqlAnalyzerService service;

    private JTextField        mapperDirField;
    private JComboBox<String> mapperFileCombo;
    private JComboBox<String> queryIdCombo;
    private JButton           analyzeButton;
    private JTextArea         resultArea;
    private JButton           copyButton;

    public SqlAnalyzerPanel(Project project) {
        this.project = project;
        this.service = new SqlAnalyzerService();
        initComponents();
    }

    /**
     * 외부(AnalyzeSqlAction)에서 XML 파일 경로를 받아 UI를 초기화한다.
     *
     * <p>파일의 부모 디렉토리를 mapperDirField에 세팅하고,
     * 파일 목록 → queryId 목록 순서로 캐스케이드 갱신한다.
     *
     * @param mapperFilePath 에디터에서 우클릭한 XML 파일의 절대 경로
     */
    public void setMapperFile(String mapperFilePath) {
        Path mapperPath = Path.of(mapperFilePath);
        Path parentDir  = mapperPath.getParent();

        mapperDirField.setText(parentDir.toString());
        reloadMapperFiles();

        // 파일명으로 Mapper File 콤보에서 선택 (상대 경로가 "." 기준이므로 파일명만 일치)
        mapperFileCombo.setSelectedItem(mapperPath.getFileName().toString());
        reloadQueryIds();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // UI 초기화
    // ──────────────────────────────────────────────────────────────────────────

    private void initComponents() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        add(buildTopPanel(),    BorderLayout.NORTH);
        add(buildResultPanel(), BorderLayout.CENTER);
        add(buildBottomPanel(), BorderLayout.SOUTH);
    }

    /** DB Settings 버튼(최상단) + 입력 폼 */
    private JPanel buildTopPanel() {
        JPanel wrapper = new JPanel(new BorderLayout(4, 4));

        JButton dbSettingsButton = new JButton("DB Settings");
        dbSettingsButton.setToolTipText("JDBC 연결 정보를 설정합니다.");
        dbSettingsButton.addActionListener(e -> new DbSettingsDialog(project).show());

        JPanel settingsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        settingsRow.add(dbSettingsButton);
        wrapper.add(settingsRow,      BorderLayout.NORTH);
        wrapper.add(buildInputPanel(), BorderLayout.CENTER);

        return wrapper;
    }

    /**
     * 입력 폼: Mapper Dir, Mapper File(상대 경로), Query ID, Analyze 버튼.
     *
     * <p>Mapper File 콤보박스는 mapper base dir 하위의 모든 XML을 재귀 탐색하여
     * 상대 경로 형태로 표시한다. depth 제한 없음.
     */
    private JPanel buildInputPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill   = GridBagConstraints.HORIZONTAL;

        // 행 0 — Mapper Dir
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        panel.add(new JLabel("Mapper Dir:"), gbc);

        mapperDirField = new JTextField();
        // Enter 키 → 즉시 파일 목록 갱신
        mapperDirField.addActionListener(e -> reloadMapperFiles());
        // 포커스 이탈 → 다른 필드로 이동 시에도 갱신
        mapperDirField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { reloadMapperFiles(); }
        });
        gbc.gridx = 1; gbc.weightx = 1.0;
        panel.add(mapperDirField, gbc);

        JButton browseButton = new JButton("...");
        browseButton.setToolTipText("매퍼 XML 루트 디렉토리를 선택합니다.");
        browseButton.addActionListener(e -> browseMapperDir());
        gbc.gridx = 2; gbc.weightx = 0;
        panel.add(browseButton, gbc);

        // 행 1 — Mapper File (재귀 탐색, 상대 경로 표시)
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        panel.add(new JLabel("Mapper File:"), gbc);

        mapperFileCombo = new JComboBox<>();
        mapperFileCombo.setToolTipText("mapper base dir 기준 상대 경로로 표시됩니다.");
        mapperFileCombo.addActionListener(e -> reloadQueryIds());
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.gridwidth = 2;
        panel.add(mapperFileCombo, gbc);
        gbc.gridwidth = 1;

        // 행 2 — Query ID + Analyze 버튼
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        panel.add(new JLabel("Query ID:"), gbc);

        queryIdCombo = new JComboBox<>();
        // editable=true: 드롭다운에 없는 queryId도 직접 입력 가능
        queryIdCombo.setEditable(true);
        gbc.gridx = 1; gbc.weightx = 1.0;
        panel.add(queryIdCombo, gbc);

        analyzeButton = new JButton("Analyze");
        analyzeButton.addActionListener(e -> runAnalysis());
        gbc.gridx = 2; gbc.weightx = 0;
        panel.add(analyzeButton, gbc);

        return panel;
    }

    /** 중앙 결과 영역: 스크롤 가능한 읽기 전용 텍스트 영역 */
    private JScrollPane buildResultPanel() {
        resultArea = new JTextArea();
        resultArea.setEditable(false);
        resultArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        resultArea.setLineWrap(true);
        resultArea.setWrapStyleWord(true);
        return new JScrollPane(resultArea);
    }

    /** 하단 클립보드 복사 버튼 */
    private JPanel buildBottomPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        copyButton = new JButton("Copy to Clipboard");
        copyButton.setEnabled(false);
        copyButton.addActionListener(e -> copyToClipboard());
        panel.add(copyButton);
        return panel;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 캐스케이드 갱신 메서드
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * mapper base dir 하위의 모든 XML 파일을 재귀 탐색하여 mapperFileCombo를 갱신한다.
     *
     * <p>표시 형식: 베이스 디렉토리 기준 상대 경로 (예: {@code payment/approval/ApprovalMapper.xml})
     * depth 제한 없이 중첩된 디렉토리 구조를 모두 처리한다.
     */
    private void reloadMapperFiles() {
        String baseDirStr = mapperDirField.getText().trim();
        mapperFileCombo.removeAllItems();

        if (baseDirStr.isBlank()) {
            return;
        }

        try {
            List<String> files = service.listXmlFiles(Path.of(baseDirStr));
            files.forEach(mapperFileCombo::addItem);
        } catch (Exception e) {
            log.warn("XML 파일 목록 로드 실패: {}", baseDirStr, e);
        }

        // 파일 목록이 갱신되면 queryId도 연쇄 갱신
        reloadQueryIds();
    }

    /**
     * 현재 선택된 XML 파일에서 DML id 목록을 읽어 queryIdCombo를 갱신한다.
     * 파싱 실패 시 log.warn 처리 후 콤보를 비운다.
     */
    private void reloadQueryIds() {
        queryIdCombo.removeAllItems();
        Path mapperFilePath = getSelectedMapperFilePath();

        if (mapperFilePath == null) {
            return;
        }

        try {
            List<String> ids = XmlQueryIdReader.readIds(mapperFilePath);
            ids.forEach(queryIdCombo::addItem);
        } catch (Exception e) {
            log.warn("queryId 목록 로드 실패: {}", mapperFilePath, e);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 헬퍼
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * IntelliJ 디렉토리 선택 다이얼로그를 열어 매퍼 베이스 디렉토리를 선택한다.
     * 선택 완료 후 파일 목록을 자동 갱신한다.
     */
    private void browseMapperDir() {
        VirtualFile chosen = FileChooser.chooseFile(
                FileChooserDescriptorFactory.createSingleFolderDescriptor(),
                project,
                null
        );

        if (chosen != null) {
            mapperDirField.setText(chosen.getPath());
            reloadMapperFiles();
        }
    }

    /**
     * mapperFileCombo에서 선택된 상대 경로를 mapper base dir 기준으로 절대 경로로 변환한다.
     * 선택 항목이 없거나 base dir가 비어있으면 null을 반환한다.
     */
    private Path getSelectedMapperFilePath() {
        String baseDirStr   = mapperDirField.getText().trim();
        String relativePath = (String) mapperFileCombo.getSelectedItem();

        if (baseDirStr.isBlank() || relativePath == null || relativePath.isBlank()) {
            return null;
        }

        return Path.of(baseDirStr).resolve(relativePath);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 분석 실행
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 분석을 IntelliJ ProgressManager 백그라운드 태스크로 실행한다.
     *
     * <p>DB EXPLAIN 및 메타데이터 조회는 수 초가 소요될 수 있으므로
     * EDT 블로킹을 방지하기 위해 반드시 백그라운드 태스크에서 실행한다.
     */
    private void runAnalysis() {
        String queryId      = (String) queryIdCombo.getSelectedItem();
        String mapperDirStr = mapperDirField.getText().trim();

        if (queryId == null || queryId.isBlank()) {
            Messages.showWarningDialog(project, "Query ID를 입력하세요.", "입력 오류");
            return;
        }

        if (mapperDirStr.isBlank()) {
            Messages.showWarningDialog(project, "Mapper Dir을 지정하세요.", "입력 오류");
            return;
        }

        // DB 설정 확인 — 미설정 시 다이얼로그로 유도
        SqlAnalyzerConfig config = DbSettingsDialog.loadConfig(project);

        if (!config.isConfigured()) {
            int answer = Messages.showYesNoDialog(
                    project,
                    "DB 연결 정보가 설정되지 않았습니다.\n지금 설정하시겠습니까?",
                    "DB 설정 필요",
                    Messages.getQuestionIcon()
            );

            if (answer == Messages.YES) {
                new DbSettingsDialog(project).show();
            }

            return;
        }

        Path selectedMapperFile = getSelectedMapperFilePath();

        if (selectedMapperFile == null) {
            Messages.showWarningDialog(project, "분석할 매퍼 파일을 선택하세요.", "입력 오류");
            return;
        }

        Path mapperBaseDir = Path.of(mapperDirStr);

        // 분석 시작 전 UI 초기화
        analyzeButton.setEnabled(false);
        copyButton.setEnabled(false);
        resultArea.setText("분석 중...");

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "MyBatis SQL 분석 중...") {
            private String    result;
            private Exception error;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                // 백그라운드 스레드 — EDT 아님
                indicator.setIndeterminate(true);
                indicator.setText("DB 분석 중...");

                try {
                    result = service.analyze(config, selectedMapperFile, mapperBaseDir, queryId);
                } catch (Exception e) {
                    error = e;
                }
            }

            @Override
            public void onSuccess() {
                // EDT에서 실행 — UI 직접 갱신 가능
                analyzeButton.setEnabled(true);

                if (error != null) {
                    resultArea.setText("오류 발생:\n" + error.getMessage());
                    log.error("SQL 분석 실패 - queryId: {}", queryId, error);
                } else if (result != null) {
                    resultArea.setText(result);
                    resultArea.setCaretPosition(0);
                    copyButton.setEnabled(true);
                }
            }

            @Override
            public void onCancel() {
                analyzeButton.setEnabled(true);
                resultArea.setText("분석이 취소되었습니다.");
            }
        });
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 클립보드
    // ──────────────────────────────────────────────────────────────────────────

    /** 결과 텍스트를 클립보드에 복사하고 2초간 버튼 레이블로 피드백을 준다. */
    private void copyToClipboard() {
        Toolkit.getDefaultToolkit()
               .getSystemClipboard()
               .setContents(new StringSelection(resultArea.getText()), null);

        copyButton.setText("Copied!");
        Timer timer = new Timer(2000, e -> copyButton.setText("Copy to Clipboard"));
        timer.setRepeats(false);
        timer.start();
    }
}
