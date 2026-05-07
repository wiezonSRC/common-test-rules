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
 * │  [DB Settings]                                           │  ← 최상단 버튼
 * ├─────────────────────────────────────────────────────────┤
 * │  Mapper Dir : [경로 입력창              ] [...]          │
 * │  Sub Dir    : [서브디렉토리 드롭다운    ]                 │
 * │  Mapper File: [XML 파일 드롭다운        ]                │
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
 * browse 버튼(또는 mapperDirField에서 Enter/포커스 이탈) → reloadSubDirs() → subdirCombo 갱신
 * → subdirCombo 선택 변경 → reloadMapperFiles() → mapperFileCombo 갱신
 * → mapperFileCombo 선택 변경 → reloadQueryIds() → queryIdCombo 갱신
 *
 * <p>IntelliJ EDT(Event Dispatch Thread) 규칙 준수:
 * - UI 컴포넌트 갱신은 반드시 EDT에서 실행 (ProgressTask의 onSuccess()는 EDT에서 호출됨)
 * - DB 연결 등 I/O 작업은 ProgressManager 백그라운드 태스크에서 실행 (EDT 블로킹 방지)
 */
@Slf4j
public class SqlAnalyzerPanel extends JPanel {

    private final Project project;
    private final SqlAnalyzerService service;

    private JTextField       mapperDirField;
    private JComboBox<String> subdirCombo;
    private JComboBox<String> mapperFileCombo;
    private JComboBox<String> queryIdCombo;
    private JButton          analyzeButton;
    private JTextArea        resultArea;
    private JButton          copyButton;

    public SqlAnalyzerPanel(Project project) {
        this.project = project;
        this.service = new SqlAnalyzerService();
        initComponents();
    }

    /**
     * 외부(AnalyzeSqlAction)에서 XML 파일 경로를 받아 UI를 초기화한다.
     *
     * <p>파일의 부모 디렉토리를 mapperDirField에 세팅하고,
     * 서브디렉토리 → 파일 목록 → queryId 목록 순서로 캐스케이드 갱신한다.
     *
     * @param mapperFilePath 에디터에서 우클릭한 XML 파일의 절대 경로
     */
    public void setMapperFile(String mapperFilePath) {
        Path mapperPath = Path.of(mapperFilePath);

        // 파일의 부모 디렉토리를 매퍼 베이스 디렉토리로 설정
        mapperDirField.setText(mapperPath.getParent().toString());

        reloadSubDirs();
        subdirCombo.setSelectedItem(".");

        reloadMapperFiles();
        mapperFileCombo.setSelectedItem(mapperPath.getFileName().toString());

        reloadQueryIds();
    }

    /**
     * 컴포넌트 초기화 및 레이아웃 배치.
     */
    private void initComponents() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        add(buildTopPanel(),    BorderLayout.NORTH);
        add(buildResultPanel(), BorderLayout.CENTER);
        add(buildBottomPanel(), BorderLayout.SOUTH);
    }

    /**
     * 최상단 DB Settings 버튼 + 입력 폼 영역.
     *
     * <p>DB Settings 버튼을 NORTH에, 입력 폼을 CENTER에 배치하여
     * 항상 최상단에 버튼이 노출되도록 한다.
     */
    private JPanel buildTopPanel() {
        JPanel wrapper = new JPanel(new BorderLayout(4, 4));

        // DB Settings 버튼 (최상단)
        JButton dbSettingsButton = new JButton("DB Settings");
        dbSettingsButton.setToolTipText("JDBC 연결 정보를 설정합니다.");
        dbSettingsButton.addActionListener(e -> {
            DbSettingsDialog dialog = new DbSettingsDialog(project);
            dialog.show();
        });

        JPanel settingsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        settingsRow.add(dbSettingsButton);
        wrapper.add(settingsRow, BorderLayout.NORTH);
        wrapper.add(buildInputPanel(), BorderLayout.CENTER);

        return wrapper;
    }

    /**
     * 입력 폼: 매퍼 디렉토리 필드, 서브디렉토리·파일·쿼리ID 드롭다운, Analyze 버튼.
     */
    private JPanel buildInputPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // 행 0: Mapper Dir 레이블 + 입력 필드 + 선택 버튼
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        panel.add(new JLabel("Mapper Dir:"), gbc);

        mapperDirField = new JTextField();
        // Enter 키: 즉시 서브디렉토리 목록 갱신
        mapperDirField.addActionListener(e -> reloadSubDirs());
        // 포커스 이탈: 다른 필드로 이동 시에도 갱신 트리거
        mapperDirField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                reloadSubDirs();
            }
        });
        gbc.gridx = 1; gbc.weightx = 1.0;
        panel.add(mapperDirField, gbc);

        JButton browseButton = new JButton("...");
        browseButton.setToolTipText("매퍼 XML 파일 디렉토리를 선택합니다.");
        browseButton.addActionListener(e -> browseMapperDir());
        gbc.gridx = 2; gbc.weightx = 0;
        panel.add(browseButton, gbc);

        // 행 1: Sub Dir 드롭다운
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        panel.add(new JLabel("Sub Dir:"), gbc);

        subdirCombo = new JComboBox<>();
        subdirCombo.addActionListener(e -> reloadMapperFiles());
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.gridwidth = 2;
        panel.add(subdirCombo, gbc);
        gbc.gridwidth = 1;

        // 행 2: Mapper File 드롭다운
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        panel.add(new JLabel("Mapper File:"), gbc);

        mapperFileCombo = new JComboBox<>();
        mapperFileCombo.addActionListener(e -> reloadQueryIds());
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.gridwidth = 2;
        panel.add(mapperFileCombo, gbc);
        gbc.gridwidth = 1;

        // 행 3: Query ID 드롭다운 + Analyze 버튼
        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0;
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

    /**
     * 중앙 결과 영역: 스크롤 가능한 읽기 전용 텍스트 영역.
     */
    private JScrollPane buildResultPanel() {
        resultArea = new JTextArea();
        resultArea.setEditable(false);
        resultArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        resultArea.setLineWrap(true);
        resultArea.setWrapStyleWord(true);
        return new JScrollPane(resultArea);
    }

    /**
     * 하단 액션 패널: 클립보드 복사 버튼 (분석 완료 전까지 비활성).
     */
    private JPanel buildBottomPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        copyButton = new JButton("Copy to Clipboard");
        copyButton.setEnabled(false);
        copyButton.addActionListener(e -> copyToClipboard());
        panel.add(copyButton);
        return panel;
    }

    /**
     * IntelliJ 디렉토리 선택 다이얼로그를 열어 매퍼 디렉토리를 선택한다.
     * 선택 완료 후 서브디렉토리 목록을 갱신한다.
     */
    private void browseMapperDir() {
        VirtualFile chosen = FileChooser.chooseFile(
                FileChooserDescriptorFactory.createSingleFolderDescriptor(),
                project,
                null
        );

        if (chosen != null) {
            mapperDirField.setText(chosen.getPath());
            reloadSubDirs();
        }
    }

    /**
     * mapperDirField 경로 기준으로 직속 서브디렉토리 목록을 갱신한다.
     * 갱신 완료 후 mapperFileCombo도 연쇄 갱신한다.
     */
    private void reloadSubDirs() {
        String baseDirStr = mapperDirField.getText().trim();
        subdirCombo.removeAllItems();

        if (baseDirStr.isBlank()) {
            return;
        }

        try {
            List<String> dirs = service.listSubDirectories(Path.of(baseDirStr));
            dirs.forEach(subdirCombo::addItem);
        } catch (Exception e) {
            log.warn("서브디렉토리 목록 로드 실패: {}", baseDirStr, e);
        }

        reloadMapperFiles();
    }

    /**
     * 현재 선택된 서브디렉토리 기준으로 XML 파일 목록을 갱신한다.
     * 갱신 완료 후 queryIdCombo도 연쇄 갱신한다.
     */
    private void reloadMapperFiles() {
        mapperFileCombo.removeAllItems();
        Path subDirPath = getSelectedSubDirPath();

        if (subDirPath == null) {
            reloadQueryIds();
            return;
        }

        try {
            List<String> files = service.listXmlFiles(subDirPath);
            files.forEach(mapperFileCombo::addItem);
        } catch (Exception e) {
            log.warn("XML 파일 목록 로드 실패: {}", subDirPath, e);
        }

        reloadQueryIds();
    }

    /**
     * 현재 선택된 XML 파일에서 queryId 목록을 읽어 드롭다운을 갱신한다.
     * 파싱 실패 시 log.warn으로 처리하고 콤보를 비운다.
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

    /**
     * 선택된 서브디렉토리의 절대 경로를 반환한다.
     * "."이면 베이스 디렉토리 자신을 반환한다.
     */
    private Path getSelectedSubDirPath() {
        String baseDirStr = mapperDirField.getText().trim();
        String subDir = (String) subdirCombo.getSelectedItem();

        if (baseDirStr.isBlank() || subDir == null) {
            return null;
        }

        Path baseDir = Path.of(baseDirStr);
        return ".".equals(subDir) ? baseDir : baseDir.resolve(subDir);
    }

    /**
     * 선택된 XML 파일의 절대 경로를 반환한다.
     */
    private Path getSelectedMapperFilePath() {
        Path subDirPath = getSelectedSubDirPath();
        String fileName = (String) mapperFileCombo.getSelectedItem();

        if (subDirPath == null || fileName == null || fileName.isBlank()) {
            return null;
        }

        return subDirPath.resolve(fileName);
    }

    /**
     * 분석을 IntelliJ ProgressManager 백그라운드 태스크로 실행한다.
     *
     * <p>백그라운드 태스크를 사용하는 이유:
     * DB EXPLAIN 실행 및 메타데이터 조회가 수 초 걸릴 수 있어,
     * EDT에서 직접 실행하면 IntelliJ UI 전체가 응답 불가 상태(freezing)가 된다.
     * ProgressManager는 진행 표시줄을 자동으로 관리하며 취소 기능도 제공한다.
     */
    private void runAnalysis() {
        String queryId     = (String) queryIdCombo.getSelectedItem();
        String mapperDirStr = mapperDirField.getText().trim();

        if (queryId == null || queryId.isBlank()) {
            Messages.showWarningDialog(project, "Query ID를 입력하세요.", "입력 오류");
            return;
        }

        if (mapperDirStr.isBlank()) {
            Messages.showWarningDialog(project, "Mapper Dir을 지정하세요.", "입력 오류");
            return;
        }

        // DB 설정 로드 및 미설정 시 설정 유도
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
        Path mapperBaseDir      = Path.of(mapperDirStr);

        if (selectedMapperFile == null) {
            Messages.showWarningDialog(project, "분석할 매퍼 파일을 선택하세요.", "입력 오류");
            return;
        }

        // 분석 시작 전 UI 상태 초기화
        analyzeButton.setEnabled(false);
        copyButton.setEnabled(false);
        resultArea.setText("분석 중...");

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "MyBatis SQL 분석 중...") {
            private String result;
            private Exception error;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                // 이 블록은 백그라운드 스레드에서 실행됨 (EDT 아님)
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
                // onSuccess()는 EDT에서 실행됨 → UI 컴포넌트 직접 갱신 가능
                analyzeButton.setEnabled(true);

                if (error != null) {
                    resultArea.setText("오류 발생:\n" + error.getMessage());
                    log.error("SQL 분석 실패 - queryId: {}", queryId, error);
                } else if (result != null) {
                    resultArea.setText(result);
                    resultArea.setCaretPosition(0); // 스크롤을 맨 위로
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

    /**
     * 결과 텍스트를 시스템 클립보드에 복사하고 2초간 버튼 레이블로 피드백을 준다.
     */
    private void copyToClipboard() {
        String text = resultArea.getText();
        Toolkit.getDefaultToolkit()
               .getSystemClipboard()
               .setContents(new StringSelection(text), null);

        // 복사 완료 피드백: 버튼 텍스트를 일시적으로 변경
        copyButton.setText("Copied!");
        Timer timer = new Timer(2000, e -> copyButton.setText("Copy to Clipboard"));
        timer.setRepeats(false);
        timer.start();
    }
}
