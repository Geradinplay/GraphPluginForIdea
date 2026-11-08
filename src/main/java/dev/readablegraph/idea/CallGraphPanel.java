package dev.readablegraph.idea;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import dev.readablegraph.core.GraphModel;
import dev.readablegraph.core.ReadableLayout;
import dev.readablegraph.core.SvgExporter;
import dev.readablegraph.ui.GraphCanvas;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Main tool window: background PSI scan, selectable focus, spacing, navigation, SVG export. */
public final class CallGraphPanel extends JPanel {
    private final Project project;
    private GraphModel model = GraphModel.empty();
    private final GraphCanvas canvas;
    private final JScrollPane scroll;
    private final JButton refresh = new JButton("Сканировать");
    private final JTextField folder = new JTextField();
    private final JButton browseFolder = new JButton("Выбрать папку…");
    private JDialog progressWindow;
    private JProgressBar progressBar;
    private JLabel progressLabel;
    private javax.swing.Timer progressTimer;
    private final AtomicReference<ProgressIndicator> activeIndicator = new AtomicReference<>();
    private boolean scanning;
    private final JButton focusButton = new JButton("В фокус");
    private final JButton fit = new JButton("Уместить");
    private final JButton zoomIn = new JButton("+");
    private final JButton zoomOut = new JButton("−");
    private final JLabel zoomLabel = new JLabel("100%");
    private final JButton exportSvg = new JButton("Экспорт SVG");
    private final JCheckBox focusOnly = new JCheckBox("Только связи выбранного класса", false);
    private final JComboBox<String> direction = new JComboBox<>(new String[]{
            "Где вызывают методы класса", "Какие методы вызывает класс"});
    private final JComboBox<Choice> chooser = new JComboBox<>();
    private final JSlider spacing = new JSlider(100, 300, 210);
    private final JLabel status = new JLabel("Выберите папку (например src) и нажмите «Сканировать».");
    private final AtomicInteger scanGeneration = new AtomicInteger();
    private final AtomicInteger layoutGeneration = new AtomicInteger();
    private boolean updatingCombo;

    public CallGraphPanel(Project project) {
        super(new BorderLayout(0, 0));
        this.project = project;
        canvas = new GraphCanvas((classId, methodId) -> {
            if (methodId != null) { navigate(classId, "@class".equals(methodId) ? null : methodId); return; }
            selectClassWithoutRebuild(classId);
        });
        scroll = new JScrollPane(canvas);
        scroll.getViewport().setBackground(new Color(247, 249, 252));
        scroll.getVerticalScrollBar().setUnitIncrement(30);
        scroll.getHorizontalScrollBar().setUnitIncrement(30);
        // When the canvas is smaller than the viewport, the wheel still works over empty space.
        scroll.getViewport().addMouseWheelListener(e -> {
            canvas.zoomAtViewportPoint(e.getPoint(), Math.pow(1.20, -e.getPreciseWheelRotation()));
            e.consume();
        });

        JPanel toolbar = new JPanel();
        toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.Y_AXIS));
        toolbar.setBorder(new EmptyBorder(6, 8, 5, 8));
        JPanel directoryRow = new JPanel(new BorderLayout(7, 0));
        directoryRow.add(new JLabel("Папка Java-исходников:"), BorderLayout.WEST);
        String base = project.getBasePath();
        if (base != null) {
            Path src = Path.of(base).resolve("src");
            folder.setText(Files.isDirectory(src) ? src.toString() : base);
        }
        directoryRow.add(folder, BorderLayout.CENTER);
        directoryRow.add(browseFolder, BorderLayout.EAST);
        toolbar.add(directoryRow);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 2));
        buttons.add(refresh);
        buttons.add(fit);
        zoomOut.setToolTipText("Отдалить диаграмму (или колесо вниз)");
        zoomIn.setToolTipText("Приблизить диаграмму (или колесо вверх)");
        buttons.add(zoomOut);
        buttons.add(zoomLabel);
        buttons.add(zoomIn);
        buttons.add(exportSvg);
        buttons.add(new JLabel("Расстояние:"));
        spacing.setMajorTickSpacing(100);
        spacing.setPaintTicks(true);
        spacing.setToolTipText("Чем больше, тем свободнее расположены классы и стрелки");
        spacing.setPreferredSize(new Dimension(145, 43));
        buttons.add(spacing);
        JPanel selectors = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 2));
        selectors.add(new JLabel("Класс:"));
        chooser.setPreferredSize(new Dimension(290, 27));
        selectors.add(chooser);
        selectors.add(focusOnly);
        selectors.add(direction);
        selectors.add(focusButton);
        toolbar.add(buttons);
        toolbar.add(selectors);
        add(toolbar, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        status.setBorder(new EmptyBorder(4, 10, 6, 10));
        add(status, BorderLayout.SOUTH);

        browseFolder.addActionListener(e -> chooseFolder());
        folder.addActionListener(e -> scan());
        refresh.addActionListener(e -> scan());
        fit.addActionListener(e -> fitGraph());
        zoomIn.addActionListener(e -> canvas.zoomAtViewportCenter(1.20));
        zoomOut.addActionListener(e -> canvas.zoomAtViewportCenter(1.0 / 1.20));
        canvas.addPropertyChangeListener("zoom", e -> zoomLabel.setText(formatZoom(canvas.getZoom())));
        exportSvg.addActionListener(e -> exportSvg());
        chooser.addActionListener(e -> { if (!updatingCombo) rebuild(); });
        focusButton.addActionListener(e -> { focusOnly.setSelected(true); rebuild(); });
        focusOnly.addActionListener(e -> rebuild());
        direction.addActionListener(e -> rebuild());
        spacing.addChangeListener(e -> { if (!spacing.getValueIsAdjusting()) rebuild(); });
        // Do not auto-scan enormous projects until the user chooses a folder.
    }

    private static String formatZoom(double zoom) {
        double percent = zoom * 100;
        return percent >= 10 ? String.format(Locale.ROOT, "%.0f%%", percent)
                : percent >= 1 ? String.format(Locale.ROOT, "%.1f%%", percent)
                : String.format(Locale.ROOT, "%.2f%%", percent);
    }

    private void fitGraph() {
        canvas.fitTo(scroll.getViewport().getExtentSize());
        scroll.getViewport().setViewPosition(new Point(0, 0));
    }

    private void chooseFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Выберите папку для анализа (.java)");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (!folder.getText().isBlank()) chooser.setCurrentDirectory(new java.io.File(folder.getText()));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
            folder.setText(chooser.getSelectedFile().getAbsolutePath());
    }

    private void beginProgress(String title) {
        if (progressWindow == null) {
            Window owner = SwingUtilities.getWindowAncestor(this);
            progressWindow = new JDialog(owner, "Readable Call Graph", Dialog.ModalityType.MODELESS);
            progressWindow.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            JPanel body = new JPanel(new BorderLayout(8, 12));
            body.setBorder(new EmptyBorder(18, 20, 16, 20));
            progressLabel = new JLabel(title);
            progressBar = new JProgressBar(0, 1000);
            progressBar.setStringPainted(true);
            progressBar.setIndeterminate(true);
            JButton cancel = new JButton("Отмена");
            cancel.addActionListener(e -> {
                ProgressIndicator indicator = activeIndicator.get();
                if (indicator != null) indicator.cancel();
                progressLabel.setText("Отмена операции…");
            });
            body.add(progressLabel, BorderLayout.NORTH);
            body.add(progressBar, BorderLayout.CENTER);
            JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            footer.add(cancel);
            body.add(footer, BorderLayout.SOUTH);
            progressWindow.setContentPane(body);
            progressWindow.setMinimumSize(new Dimension(420, 145));
            progressWindow.pack();
            progressWindow.setLocationRelativeTo(this);
            progressTimer = new javax.swing.Timer(180, e -> {
                ProgressIndicator indicator = activeIndicator.get();
                if (indicator == null) return;
                String message = indicator.getText();
                String file = indicator.getText2();
                progressLabel.setText("<html>" + html(message == null ? title : message)
                        + "<br><small>" + html(file == null ? "" : file) + "</small></html>");
                progressBar.setIndeterminate(indicator.isIndeterminate());
                if (!indicator.isIndeterminate()) {
                    progressBar.setValue((int) Math.round(indicator.getFraction() * 1000));
                    progressBar.setString((int) Math.round(indicator.getFraction() * 100) + "%");
                }
            });
        }
        progressLabel.setText(title);
        progressBar.setIndeterminate(true);
        progressTimer.start();
        progressWindow.setVisible(true);
    }

    private static String html(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void endProgress() {
        activeIndicator.set(null);
        if (progressTimer != null) progressTimer.stop();
        if (progressWindow != null) { progressWindow.dispose(); progressWindow = null; }
    }

    private void scan() {
        Path root;
        try { root = Path.of(folder.getText().trim()).toAbsolutePath().normalize(); }
        catch (RuntimeException invalid) { status.setText("Укажите корректную папку src."); return; }
        if (!Files.isDirectory(root)) {
            status.setText("Папка не существует: " + root);
            return;
        }
        if (DumbService.isDumb(project)) {
            status.setText("Индексирование проекта… сканирование начнётся после индексации.");
            DumbService.getInstance(project).runWhenSmart(this::scan);
            return;
        }
        int request = scanGeneration.incrementAndGet();
        layoutGeneration.incrementAndGet();
        scanning = true;
        refresh.setEnabled(false);
        status.setText("Сканирование: " + root);
        beginProgress("Поиск Java-файлов и классов…");
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Readable Call Graph: scan", true) {
            private GraphModel scanned;
            @Override public void run(@NotNull ProgressIndicator indicator) {
                activeIndicator.set(indicator);
                indicator.setIndeterminate(false);
                scanned = JavaCallScanner.scan(project, root, indicator);
            }
            @Override public void onSuccess() {
                if (request != scanGeneration.get() || project.isDisposed()) return;
                scanning = false;
                model = scanned;
                updateChoices();
                refresh.setEnabled(true);
                if (model.classes.isEmpty()) {
                    endProgress();
                    canvas.setLayoutResult(new ReadableLayout().layout(GraphModel.empty(), spacing.getValue()));
                    status.setText("В папке нет индексированных Java-классов. Папка должна входить в Source Root проекта.");
                } else rebuild();
            }
            @Override public void onCancel() {
                if (request == scanGeneration.get()) {
                    scanning = false; refresh.setEnabled(true); endProgress();
                    status.setText("Анализ отменён.");
                }
            }
            @Override public void onThrowable(@NotNull Throwable e) {
                if (request == scanGeneration.get()) {
                    scanning = false; refresh.setEnabled(true); endProgress();
                    status.setText("Ошибка анализа: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        });
    }

    private void updateChoices() {
        String prev = selectedId();
        HashMap<String, Integer> activity = new HashMap<>();
        for (GraphModel.CallEdge e : model.edges) {
            activity.merge(e.fromClass(), 1, Integer::sum);
            activity.merge(e.toClass(), 1, Integer::sum);
        }
        ArrayList<GraphModel.ClassNode> values = new ArrayList<>(model.classes.values());
        values.sort(Comparator.comparing(GraphModel.ClassNode::id));
        updatingCombo = true;
        try {
            java.util.Vector<Choice> choices = new java.util.Vector<>(values.size());
            for (GraphModel.ClassNode c : values) choices.add(new Choice(c.id(), c.displayName()));
            chooser.setModel(new DefaultComboBoxModel<>(choices));
            String choose = prev;
            if (choose == null || !model.classes.containsKey(choose)) {
                choose = values.stream().max(Comparator.<GraphModel.ClassNode>comparingInt(c -> activity.getOrDefault(c.id(), 0))
                        .thenComparing(GraphModel.ClassNode::id)).map(GraphModel.ClassNode::id).orElse(null);
            }
            for (int i = 0; i < chooser.getItemCount(); i++)
                if (chooser.getItemAt(i).id.equals(choose)) { chooser.setSelectedIndex(i); break; }
        } finally { updatingCombo = false; }
    }

    private record Choice(String id, String name) {
        @Override public String toString() { return name + "  —  " + id; }
    }
    private String selectedId() {
        Object selected = chooser.getSelectedItem();
        return selected instanceof Choice c ? c.id : null;
    }

    private void selectClassWithoutRebuild(String id) {
        if (id == null) return;
        updatingCombo = true;
        try {
            for (int i = 0; i < chooser.getItemCount(); i++)
                if (chooser.getItemAt(i).id.equals(id)) { chooser.setSelectedIndex(i); break; }
        } finally { updatingCombo = false; }
        status.setText("Выбран " + id + ". Нажмите «В фокус», чтобы перестроить связи вокруг него.");
    }

    private void rebuild() {
        if (scanning) return;
        if (model.classes.isEmpty()) {
            endProgress();
            canvas.setLayoutResult(new ReadableLayout().layout(GraphModel.empty(), spacing.getValue()));
            return;
        }
        String id = selectedId();
        if (id == null) { endProgress(); return; }
        final boolean useFocus = focusOnly.isSelected();
        // Do not discard any method, class, or edge when the user requests the full diagram.
        final GraphModel diagram = useFocus ? focused(id) : model;
        int generationId = layoutGeneration.incrementAndGet();
        final int currentSpacing = spacing.getValue();
        status.setText("Раскладка: " + diagram.classes.size() + " классов, " + diagram.edges.size() + " связей…");
        beginProgress("Построение полной диаграммы…");
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Readable Call Graph: layout", true) {
            private ReadableLayout.Result result;
            @Override public void run(@NotNull ProgressIndicator indicator) {
                activeIndicator.set(indicator);
                indicator.setIndeterminate(true);
                indicator.setText("Раскладка " + diagram.classes.size() + " классов, " + diagram.edges.size() + " связей");
                ReadableLayout engine = new ReadableLayout();
                result = useFocus ? engine.layoutFocus(diagram, id, currentSpacing)
                        : engine.layout(diagram, currentSpacing);
                indicator.checkCanceled();
            }
            @Override public void onSuccess() {
                if (generationId != layoutGeneration.get() || project.isDisposed()) return;
                endProgress();
                canvas.setLayoutResult(result);
                canvas.setSelectedClass(id);
                // Start with a complete overview instead of one enormous class.
                // The user can zoom toward any region with the mouse wheel.
                fitGraph();
                status.setText("Проиндексировано: " + model.classes.size() + " классов, " + model.edges.size()
                        + " связей | Нарисовано: " + diagram.classes.size() + " классов, " + diagram.edges.size()
                        + " стрелок | Колесо: масштаб | двойной щелчок: код");
            }
            @Override public void onCancel() {
                if (generationId == layoutGeneration.get()) { endProgress(); status.setText("Раскладка отменена."); }
            }
            @Override public void onThrowable(@NotNull Throwable e) {
                if (generationId == layoutGeneration.get()) {
                    endProgress();
                    status.setText("Ошибка раскладки: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        });
    }

    private GraphModel focused(String id) {
        boolean outward = direction.getSelectedIndex() == 0;
        HashMap<String, Integer> frequency = new HashMap<>();
        for (GraphModel.CallEdge e : model.edges) {
            if (outward && e.fromClass().equals(id) && !e.toClass().equals(id))
                frequency.merge(e.toClass(), e.count(), Integer::sum);
            else if (!outward && e.toClass().equals(id) && !e.fromClass().equals(id))
                frequency.merge(e.fromClass(), e.count(), Integer::sum);
        }
        ArrayList<String> neighbors = new ArrayList<>(frequency.keySet());
        neighbors.sort(Comparator.<String>comparingInt(k -> -frequency.get(k)).thenComparing(k -> k));
        LinkedHashSet<String> keep = new LinkedHashSet<>();
        keep.add(id);
        keep.addAll(neighbors);
        GraphModel slice = model.subgraph(keep);
        ArrayList<GraphModel.CallEdge> edges = new ArrayList<>();
        for (GraphModel.CallEdge e : slice.edges)
            if (outward ? e.fromClass().equals(id) : e.toClass().equals(id)) edges.add(e);
        edges.sort(Comparator.<GraphModel.CallEdge>comparingInt(e -> -e.count())
                .thenComparing(GraphModel.CallEdge::methodId));
        return new GraphModel(slice.classes.values(), edges);
    }

    private void navigate(String id, String methodId) {
        ReadAction.nonBlocking(() -> {
            PsiClass cls = JavaPsiFacade.getInstance(project).findClass(id, GlobalSearchScope.projectScope(project));
            if (cls == null) return null;
            PsiElement target = cls;
            if (methodId != null)
                for (PsiMethod m : cls.getMethods())
                    if (methodId.equals(JavaCallScanner.methodId(m))) { target = m; break; }
            PsiFile f = target.getContainingFile();
            if (f == null || f.getVirtualFile() == null) return null;
            return new OpenFileDescriptor(project, f.getVirtualFile(), target.getTextOffset());
        }).finishOnUiThread(com.intellij.openapi.application.ModalityState.any(), result -> {
            if (result != null && !project.isDisposed()) result.navigate(true);
        }).submit(com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService());
    }

    private void exportSvg() {
        if (canvas.getLayoutResult().classes().isEmpty()) { status.setText("Сначала постройте схему."); return; }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Сохранить читаемую схему классов в SVG");
        chooser.setSelectedFile(new java.io.File("call-graph.svg"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        java.io.File chosen = chooser.getSelectedFile();
        Path target = chosen.toPath();
        if (!chosen.getName().toLowerCase(Locale.ROOT).endsWith(".svg"))
            target = target.resolveSibling(chosen.getName() + ".svg");
        try {
            Files.writeString(target, SvgExporter.export(canvas.getLayoutResult()), StandardCharsets.UTF_8);
            status.setText("SVG сохранён: " + target.toAbsolutePath());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Не удалось сохранить SVG: " + ex.getMessage(),
                    "Ошибка экспорта", JOptionPane.ERROR_MESSAGE);
        }
    }
}
