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
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.ArrayList;
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

    /** mapperFileCombo 검색 필터링을 위한 전체 파일 목록 캐시 */
    private List<String> allMapperFiles  = new ArrayList<>();

    /**
     * Mapper File 필터링 도중 removeAllItems()/addItem() 이 유발하는 ActionEvent의
     * cascade를 막기 위한 가드 플래그.
     * true인 동안 mapperFileCombo ActionListener가 reloadQueryIds()를 호출하지 않는다.
     */
    private boolean isUpdatingFilter = false;

    /** queryIdCombo 검색 필터링을 위한 전체 Query ID 목록 캐시 */
    private List<String> allQueryIds = new ArrayList<>();

    /**
     * Query ID 필터링 도중 removeAllItems()/addItem() 이 유발하는 이벤트 cascade를 막는 플래그.
     * mapperFileCombo의 isUpdatingFilter와 독립적으로 관리한다.
     */
    private boolean isUpdatingQueryFilter = false;

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
        // editable=true: 키보드 입력으로 파일명을 검색/필터링할 수 있다
        mapperFileCombo.setEditable(true);
        mapperFileCombo.setToolTipText("파일명을 입력하면 실시간으로 필터링됩니다. 목록에서 선택하면 Query ID가 자동으로 로드됩니다.");

        // ActionListener: 드롭다운에서 항목을 선택하거나 Enter를 눌렀을 때만 queryId 갱신
        // isUpdatingFilter=true인 필터링 도중에는 무시하여 불필요한 cascade 방지
        mapperFileCombo.addActionListener(e -> {
            if (!isUpdatingFilter) {
                reloadQueryIds();
            }
        });

        // KeyAdapter: 실제 키 입력에만 반응하여 필터링
        //
        // DocumentListener를 쓰면 드롭다운 항목 선택 시 내부적으로 setText()가 호출되는데,
        // DocumentListener는 문서 write-lock을 보유한 채 동기 호출되므로
        // filterMapperFiles() 안에서 addItem() → setText()를 재호출하면
        // AbstractDocument.writeLock()이 "Attempt to mutate in notification"을 던진다.
        //
        // KeyAdapter는 실제 키보드 이벤트에만 반응하며, 프로그래밍 방식의 setText()
        // (드롭다운 선택 시 내부 호출)에는 반응하지 않으므로 재진입 문제가 없다.
        JTextField mapperFileEditor = (JTextField) mapperFileCombo.getEditor().getEditorComponent();
        mapperFileEditor.addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                int keyCode = e.getKeyCode();

                // 탐색·닫기 키는 콤보박스 내부 핸들러가 처리
                if (keyCode == KeyEvent.VK_ESCAPE ||
                    keyCode == KeyEvent.VK_UP     ||
                    keyCode == KeyEvent.VK_DOWN) {
                    return;
                }

                if (keyCode == KeyEvent.VK_ENTER) {
                    // ── Enter 처리 타이밍 문제 ──────────────────────────────────────────────
                    // keyReleased는 JComboBox의 keyPressed(팝업 닫기 + selectedItem 커밋) 이후에 실행된다.
                    // 즉, 이 시점에 JComboBox는 이미 editor 텍스트(검색 키워드)를 selectedItem으로
                    // 커밋한 상태이므로, getSelectedItem()이 유효한 파일 경로 대신 키워드를 반환한다.
                    //
                    // 해결: editor 텍스트가 allMapperFiles에 없는 키워드라면 → 유효한 항목으로 교정한다.
                    //   항목 선택 우선순위:
                    //     1. 화살표 키로 탐색하여 selectedIndex가 유효한 경우 → 해당 항목
                    //     2. 탐색 없이 바로 Enter(selectedIndex == -1) → 첫 번째 항목(index 0)
                    String editorText = mapperFileEditor.getText();

                    if (!allMapperFiles.contains(editorText) && mapperFileCombo.getItemCount() > 0) {
                        int idx = mapperFileCombo.getSelectedIndex();
                        String itemToSelect = (idx >= 0 && idx < mapperFileCombo.getItemCount())
                                ? mapperFileCombo.getItemAt(idx)
                                : mapperFileCombo.getItemAt(0);
                        isUpdatingFilter = true;
                        try {
                            mapperFileCombo.setSelectedItem(itemToSelect);
                            mapperFileEditor.setText(itemToSelect);
                            mapperFileCombo.hidePopup();
                        } finally {
                            isUpdatingFilter = false;
                        }
                        reloadQueryIds();
                    }
                    return;
                }

                filterMapperFiles();
            }
        });

        gbc.gridx = 1; gbc.weightx = 1.0; gbc.gridwidth = 2;
        panel.add(mapperFileCombo, gbc);
        gbc.gridwidth = 1;

        // 행 2 — Query ID + Analyze 버튼
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        panel.add(new JLabel("Query ID:"), gbc);

        queryIdCombo = new JComboBox<>();
        // editable=true: 키보드 타이핑으로 검색·필터링 및 미목록 queryId 직접 입력 가능
        queryIdCombo.setEditable(true);
        queryIdCombo.setToolTipText("Query ID를 입력하면 실시간으로 필터링됩니다.");

        // KeyAdapter: 타이핑 → filterQueryIds(), Enter(1건) → 자동 선택
        JTextField queryIdEditor = (JTextField) queryIdCombo.getEditor().getEditorComponent();
        queryIdEditor.addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                int keyCode = e.getKeyCode();

                if (keyCode == KeyEvent.VK_ESCAPE ||
                    keyCode == KeyEvent.VK_UP     ||
                    keyCode == KeyEvent.VK_DOWN) {
                    return;
                }

                if (keyCode == KeyEvent.VK_ENTER) {
                    // mapperFileCombo와 동일한 Enter 교정 로직:
                    // JComboBox keyPressed가 이미 키워드를 selectedItem으로 커밋했으므로
                    // allQueryIds에 없는 키워드라면 → 적절한 항목으로 교정한다.
                    //   1. 화살표 탐색으로 selectedIndex가 유효하면 해당 항목
                    //   2. 그 외 → 첫 번째 항목(index 0)
                    String editorText = queryIdEditor.getText();

                    if (!allQueryIds.contains(editorText) && queryIdCombo.getItemCount() > 0) {
                        int idx = queryIdCombo.getSelectedIndex();
                        String itemToSelect = (idx >= 0 && idx < queryIdCombo.getItemCount())
                                ? queryIdCombo.getItemAt(idx)
                                : queryIdCombo.getItemAt(0);
                        isUpdatingQueryFilter = true;
                        try {
                            queryIdCombo.setSelectedItem(itemToSelect);
                            queryIdEditor.setText(itemToSelect);
                            queryIdCombo.hidePopup();
                        } finally {
                            isUpdatingQueryFilter = false;
                        }
                    }
                    return;
                }

                filterQueryIds();
            }
        });

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
     *
     * <p>로드된 전체 목록을 {@code allMapperFiles}에 캐시하여 이후 검색 필터링의 원본으로 사용한다.
     * 콤보 갱신 시 에디터 텍스트를 초기화하고 isUpdatingFilter 플래그로 불필요한 cascade를 억제한다.
     */
    private void reloadMapperFiles() {
        String baseDirStr = mapperDirField.getText().trim();

        // 전체 캐시 및 콤보 초기화 — 이벤트 cascade 억제
        allMapperFiles.clear();
        isUpdatingFilter = true;
        try {
            mapperFileCombo.removeAllItems();

            // 에디터 텍스트(검색어)도 함께 초기화
            JTextField editor = (JTextField) mapperFileCombo.getEditor().getEditorComponent();
            editor.setText("");
        } finally {
            isUpdatingFilter = false;
        }

        if (baseDirStr.isBlank()) {
            return;
        }

        try {
            List<String> files = service.listXmlFiles(Path.of(baseDirStr));
            allMapperFiles.addAll(files);

            // 전체 목록으로 콤보 채우기 — 이벤트 cascade 억제
            isUpdatingFilter = true;
            try {
                files.forEach(mapperFileCombo::addItem);
            } finally {
                isUpdatingFilter = false;
            }
        } catch (Exception e) {
            log.warn("XML 파일 목록 로드 실패: {}", baseDirStr, e);
        }

        // 파일 목록이 갱신되면 queryId도 연쇄 갱신
        reloadQueryIds();
    }

    /**
     * 현재 선택된 XML 파일에서 DML id 목록을 읽어 queryIdCombo를 갱신한다.
     *
     * <p>전체 목록을 {@code allQueryIds}에 캐시하여 이후 검색 필터링의 원본으로 사용한다.
     * 파싱 실패 시 log.warn 처리 후 콤보를 비운다.
     */
    private void reloadQueryIds() {
        // 캐시 및 콤보 초기화 — cascade 억제
        allQueryIds.clear();
        isUpdatingQueryFilter = true;
        try {
            queryIdCombo.removeAllItems();
            JTextField editor = (JTextField) queryIdCombo.getEditor().getEditorComponent();
            editor.setText("");
        } finally {
            isUpdatingQueryFilter = false;
        }

        Path mapperFilePath = getSelectedMapperFilePath();

        if (mapperFilePath == null) {
            return;
        }

        try {
            List<String> ids = XmlQueryIdReader.readIds(mapperFilePath);
            allQueryIds.addAll(ids);

            isUpdatingQueryFilter = true;
            try {
                ids.forEach(queryIdCombo::addItem);
            } finally {
                isUpdatingQueryFilter = false;
            }
        } catch (Exception e) {
            log.warn("queryId 목록 로드 실패: {}", mapperFilePath, e);
        }
    }

    /**
     * 에디터에 입력된 키워드로 {@code allMapperFiles}를 필터링하여 mapperFileCombo 목록을 갱신한다.
     *
     * <p>동작 방식:
     * <ol>
     *   <li>에디터 현재 텍스트(키워드)를 캡처한다.</li>
     *   <li>{@code isUpdatingFilter = true}로 설정하여 removeAllItems/addItem이 유발하는
     *       ActionEvent를 차단한다 (queryId cascade 방지).</li>
     *   <li>키워드가 포함된 파일명만 콤보에 재추가한다(대소문자 무시, contains 방식).</li>
     *   <li>에디터 텍스트를 캡처한 키워드로 복원하고 커서를 끝으로 이동한다.</li>
     *   <li>매칭 결과가 있으면 드롭다운 팝업을 자동으로 표시한다.</li>
     * </ol>
     *
     * <p>KeyAdapter에서 호출되므로 문서 알림(notification) 컨텍스트 바깥에서 실행된다.
     * 사용자가 드롭다운에서 항목을 클릭하면 {@code isUpdatingFilter = false} 상태에서
     * ActionListener가 실행되어 {@code reloadQueryIds()}가 호출된다.
     */
    private void filterMapperFiles() {
        // isUpdatingFilter가 true인 동안(reloadMapperFiles 실행 중)에는 필터링을 건너뜀.
        // KeyAdapter는 프로그래밍적 addItem()/setText()에 반응하지 않으므로
        // 실제로 이 가드가 발동될 경우는 거의 없지만, 방어적 안전 장치로 유지한다.
        if (isUpdatingFilter) {
            return;
        }

        JTextField editor  = (JTextField) mapperFileCombo.getEditor().getEditorComponent();
        // removeAllItems()는 내부적으로 editor 텍스트를 "" 로 초기화한다.
        // 키워드를 그 전에 캡처해두지 않으면 필터링 기준을 잃게 된다.
        String     keyword = editor.getText();
        String     lower   = keyword.toLowerCase();

        isUpdatingFilter = true;
        try {
            mapperFileCombo.removeAllItems();

            List<String> filtered = lower.isBlank()
                    ? allMapperFiles
                    : allMapperFiles.stream()
                                    .filter(f -> f.toLowerCase().contains(lower))
                                    .collect(java.util.stream.Collectors.toList());

            filtered.forEach(mapperFileCombo::addItem);

            // addItem()이 에디터를 첫 번째 항목으로 덮어쓰므로 원래 키워드로 복원
            editor.setText(keyword);
            editor.setCaretPosition(keyword.length());

            // 검색어가 있고 매칭 결과가 존재하면 팝업 자동 표시
            if (!lower.isBlank() && !filtered.isEmpty()) {
                mapperFileCombo.showPopup();
            }
        } finally {
            isUpdatingFilter = false;
        }
    }

    /**
     * 에디터에 입력된 키워드로 {@code allQueryIds}를 필터링하여 queryIdCombo 목록을 갱신한다.
     *
     * <p>filterMapperFiles()와 동일한 패턴을 사용한다:
     * <ol>
     *   <li>에디터 텍스트(키워드)를 캡처한다.</li>
     *   <li>{@code isUpdatingQueryFilter = true}로 설정하여 addItem()의 이벤트 cascade를 차단한다.</li>
     *   <li>키워드를 포함하는 id만 콤보에 재추가한다(대소문자 무시, contains 방식).</li>
     *   <li>에디터 텍스트를 키워드로 복원하고 팝업을 표시한다.</li>
     * </ol>
     */
    private void filterQueryIds() {
        // isUpdatingQueryFilter가 true인 동안(reloadQueryIds 실행 중)에는 필터링을 건너뜀.
        // filterMapperFiles()와 동일한 방어적 가드.
        if (isUpdatingQueryFilter) {
            return;
        }

        JTextField editor  = (JTextField) queryIdCombo.getEditor().getEditorComponent();
        // removeAllItems()가 editor 텍스트를 초기화하기 전에 키워드를 미리 캡처한다.
        String     keyword = editor.getText();
        String     lower   = keyword.toLowerCase();

        isUpdatingQueryFilter = true;
        try {
            queryIdCombo.removeAllItems();

            List<String> filtered = lower.isBlank()
                    ? allQueryIds
                    : allQueryIds.stream()
                                 .filter(id -> id.toLowerCase().contains(lower))
                                 .collect(java.util.stream.Collectors.toList());

            filtered.forEach(queryIdCombo::addItem);

            // addItem()이 에디터를 첫 번째 항목으로 덮어쓰므로 키워드 복원
            editor.setText(keyword);
            editor.setCaretPosition(keyword.length());

            if (!lower.isBlank() && !filtered.isEmpty()) {
                queryIdCombo.showPopup();
            }
        } finally {
            isUpdatingQueryFilter = false;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 헬퍼
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * IntelliJ 디렉토리 선택 다이얼로그를 열어 매퍼 베이스 디렉토리를 선택한다.
     *
     * <p>다이얼로그 초기 위치를 현재 프로젝트 루트로 설정한다.
     * mapperDirField에 이미 경로가 입력된 경우 해당 경로를 초기 위치로 우선 사용한다.
     * 선택 완료 후 파일 목록을 자동 갱신한다.
     */
    private void browseMapperDir() {
        // 초기 위치 결정: 입력된 경로 → 프로젝트 루트 → null(시스템 기본) 순으로 폴백
        VirtualFile initialDir = resolveInitialBrowseDir();

        VirtualFile chosen = FileChooser.chooseFile(
                FileChooserDescriptorFactory.createSingleFolderDescriptor(),
                project,
                initialDir
        );

        if (chosen != null) {
            mapperDirField.setText(chosen.getPath());
            reloadMapperFiles();
        }
    }

    /**
     * 파일 선택 다이얼로그의 초기 디렉토리를 결정한다.
     *
     * <ol>
     *   <li>mapperDirField에 유효한 경로가 입력된 경우 → 해당 경로</li>
     *   <li>입력이 없거나 경로가 존재하지 않는 경우 → 현재 프로젝트 루트</li>
     *   <li>프로젝트 루트도 확인 불가한 경우 → null (IntelliJ 기본 동작)</li>
     * </ol>
     */
    private VirtualFile resolveInitialBrowseDir() {
        // 1. 이미 입력된 경로가 있으면 해당 경로 우선 사용
        String currentText = mapperDirField.getText().trim();
        if (!currentText.isBlank()) {
            VirtualFile existing = LocalFileSystem.getInstance().findFileByPath(currentText);
            if (existing != null && existing.isDirectory()) {
                return existing;
            }
        }

        // 2. 현재 프로젝트 루트를 초기 위치로 사용
        String basePath = project.getBasePath();
        if (basePath != null) {
            return LocalFileSystem.getInstance().findFileByPath(basePath);
        }

        return null;
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
