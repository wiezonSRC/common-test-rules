package com.example.sqlanalyzer.intellij.toolwindow;

import com.example.sqlanalyzer.intellij.service.SqlAnalyzerService;
import com.intellij.openapi.application.ApplicationManager;
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
import java.nio.file.Path;
import java.util.List;

/**
 * MyBatis SQL Analyzer Tool Window의 메인 UI 패널.
 *
 * <p>레이아웃 구조:
 * <pre>
 * ┌─ MyBatis SQL Analyzer ──────────────────────┐
 * │  Mapper Dir : [경로 입력창          ] [...]  │  ← 상단 입력 폼
 * │  Query ID   : [드롭다운             ] [▼]   │
 * │                             [Analyze]        │
 * ├─────────────────────────────────────────────┤
 * │  (분석 결과 / AI 프롬프트 텍스트)             │  ← 중앙 결과 영역
 * ├─────────────────────────────────────────────┤
 * │                   [Copy to Clipboard]        │  ← 하단 액션 버튼
 * └─────────────────────────────────────────────┘
 * </pre>
 *
 * <p>IntelliJ EDT(Event Dispatch Thread) 규칙 준수:
 * - UI 컴포넌트 갱신은 반드시 EDT에서 실행 (ProgressTask의 onSuccess()는 EDT에서 호출됨)
 * - DB 연결 등 I/O 작업은 ProgressManager 백그라운드 태스크에서 실행 (EDT 블로킹 방지)
 */
@Slf4j
public class SqlAnalyzerPanel extends JPanel {

    private final Project project;
    private final SqlAnalyzerService service;

    private JTextField mapperDirField;
    private JComboBox<String> queryIdCombo;
    private JButton analyzeButton;
    private JTextArea resultArea;
    private JButton copyButton;

    public SqlAnalyzerPanel(Project project) {
        this.project = project;
        this.service = new SqlAnalyzerService();
        initComponents();
    }

    /**
     * 외부(AnalyzeSqlAction)에서 XML 파일 경로를 받아 UI를 초기화한다.
     *
     * <p>매퍼 파일의 부모 디렉토리를 mapperDir 필드에 자동 세팅하고,
     * 해당 XML 파일의 DML id 목록을 드롭다운에 로드한다.
     *
     * @param mapperFilePath 에디터에서 우클릭한 XML 파일의 절대 경로
     */
    public void setMapperFile(String mapperFilePath) {
        Path mapperPath = Path.of(mapperFilePath);

        // 파일의 부모 디렉토리를 매퍼 베이스 디렉토리로 설정
        mapperDirField.setText(mapperPath.getParent().toString());

        // 해당 XML에 정의된 queryId 목록을 드롭다운에 로드
        loadQueryIds(mapperFilePath);
    }

    /**
     * XML 파일에서 DML 태그의 id 목록을 읽어 드롭다운(queryIdCombo)을 갱신한다.
     *
     * <p>파싱 실패 시 사용자에게 오류 다이얼로그를 표시하며, 드롭다운은 비워 둔다.
     */
    private void loadQueryIds(String mapperFilePath) {
        queryIdCombo.removeAllItems();

        try {
            List<String> ids = XmlQueryIdReader.readIds(Path.of(mapperFilePath));
            ids.forEach(queryIdCombo::addItem);
        } catch (Exception e) {
            log.error("queryId 목록 로드 실패: {}", mapperFilePath, e);
            Messages.showErrorDialog(project,
                    "queryId 목록을 불러올 수 없습니다:\n" + e.getMessage(), "파싱 오류");
        }
    }

    /**
     * 컴포넌트 초기화 및 레이아웃 배치.
     */
    private void initComponents() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        add(buildInputPanel(), BorderLayout.NORTH);
        add(buildResultPanel(), BorderLayout.CENTER);
        add(buildBottomPanel(), BorderLayout.SOUTH);
    }

    /**
     * 상단 입력 폼: 매퍼 디렉토리 필드, queryId 드롭다운, Analyze 버튼.
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
        gbc.gridx = 1; gbc.weightx = 1.0;
        panel.add(mapperDirField, gbc);

        JButton browseButton = new JButton("...");
        browseButton.setToolTipText("매퍼 XML 파일 디렉토리를 선택합니다.");
        browseButton.addActionListener(e -> browseMapperDir());
        gbc.gridx = 2; gbc.weightx = 0;
        panel.add(browseButton, gbc);

        // 행 1: Query ID 레이블 + 드롭다운 + Analyze 버튼
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
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
     */
    private void browseMapperDir() {
        VirtualFile chosen = FileChooser.chooseFile(
                FileChooserDescriptorFactory.createSingleFolderDescriptor(),
                project,
                null
        );

        if (chosen != null) {
            mapperDirField.setText(chosen.getPath());
        }
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
        String queryId = (String) queryIdCombo.getSelectedItem();
        String mapperDir = mapperDirField.getText().trim();

        if (queryId == null || queryId.isBlank()) {
            Messages.showWarningDialog(project, "Query ID를 입력하세요.", "입력 오류");
            return;
        }

        if (mapperDir.isBlank()) {
            Messages.showWarningDialog(project, "Mapper Dir을 지정하세요.", "입력 오류");
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
                indicator.setText("매퍼 파일 검색 중...");

                try {
                    Path mapperDirPath = Path.of(mapperDir);
                    List<Path> matchedFiles = service.findMatchingFiles(mapperDirPath, queryId);

                    if (matchedFiles.isEmpty()) {
                        error = new IllegalArgumentException(
                                "'" + queryId + "' 를 포함하는 매퍼 파일을 찾을 수 없습니다.\n" +
                                "Mapper Dir 경로와 queryId를 확인하세요."
                        );
                        return;
                    }

                    Path selectedFile;

                    if (matchedFiles.size() == 1) {
                        selectedFile = matchedFiles.get(0);
                    } else {
                        // 여러 파일에 동일 queryId가 있을 때: EDT에서 선택 다이얼로그 표시
                        selectedFile = selectFileFromDialog(matchedFiles);
                    }

                    // 사용자가 다이얼로그를 취소한 경우
                    if (selectedFile == null) {
                        return;
                    }

                    indicator.setText("DB 분석 중...");
                    result = service.analyze(project.getBasePath(), selectedFile, queryId);

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
     * 동일한 queryId를 포함하는 여러 파일 중 하나를 사용자가 선택하도록 다이얼로그를 표시한다.
     *
     * <p>백그라운드 스레드에서 호출되므로 invokeAndWait으로 EDT 전환 후 다이얼로그를 표시한다.
     * invokeAndWait을 사용하는 이유: 다이얼로그 결과(사용자 선택)를 백그라운드 스레드로
     * 돌아온 후에도 사용해야 하므로 비동기(invokeLater)가 아닌 동기 방식이 필요하다.
     *
     * @param files queryId를 포함하는 파일 목록
     * @return 사용자가 선택한 파일, 취소 시 null
     */
    private Path selectFileFromDialog(List<Path> files) {
        String[] options = files.stream()
                .map(p -> p.getFileName().toString())
                .toArray(String[]::new);

        int[] choiceIndex = {-1};

        ApplicationManager.getApplication().invokeAndWait(() -> {
            choiceIndex[0] = Messages.showChooseDialog(
                    project,
                    "동일한 queryId가 여러 파일에 존재합니다.\n분석할 파일을 선택하세요.",
                    "매퍼 파일 선택",
                    Messages.getQuestionIcon(),
                    options,
                    options[0]
            );
        });

        return choiceIndex[0] >= 0 ? files.get(choiceIndex[0]) : null;
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
