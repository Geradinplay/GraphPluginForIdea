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

/** Main tool window: background PSI scan, selectable focus, spacing, navigation, SVG export. */
public final class CallGraphPanel extends JPanel {
    private final Project project;
    private GraphModel model = GraphModel.empty();
    private final GraphCanvas canvas;
    private final JScrollPane scroll;
    private final JButton refresh = new JButton("Обновить");
    private final JButton focusButton = new JButton("В фокус");
    private final JButton fit = new JButton("Уместить");
    private final JButton exportSvg = new JButton("Экспорт SVG");
    private final JCheckBox focusOnly = new JCheckBox("Фокус (без паутины)", true);
    private final JComboBox<String> direction = new JComboBox<>(new String[]{
            "Где вызывают методы класса", "Какие методы вызывает класс"});
    private final JComboBox<Choice> chooser = new JComboBox<>();
    private final JSlider spacing = new JSlider(100, 300, 210);
    private final JLabel status = new JLabel("Нажмите «Обновить», чтобы проанализировать Java-проект.");
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

        JPanel toolbar = new JPanel();
        toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.Y_AXIS));
        toolbar.setBorder(new EmptyBorder(6, 8, 5, 8));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 2));
        buttons.add(refresh);
        buttons.add(fit);
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

        refresh.addActionListener(e -> scan());
        fit.addActionListener(e -> { canvas.fitTo(scroll.getViewport().getExtentSize()); scroll.getViewport().setViewPosition(new Point(0, 0)); });
        exportSvg.addActionListener(e -> exportSvg());
        chooser.addActionListener(e -> { if (!updatingCombo) rebuild(); });
        focusButton.addActionListener(e -> { focusOnly.setSelected(true); rebuild(); });
        focusOnly.addActionListener(e -> rebuild());
        direction.addActionListener(e -> rebuild());
        spacing.addChangeListener(e -> { if (!spacing.getValueIsAdjusting()) rebuild(); });
        SwingUtilities.invokeLater(this::scan);
    }

    private void scan() {
        if (DumbService.isDumb(project)) {
            status.setText("Индексирование проекта… анализ запустится после завершения.");
            DumbService.getInstance(project).runWhenSmart(this::scan);
            return;
        }
        int request = scanGeneration.incrementAndGet();
        refresh.setEnabled(false);
        layoutGeneration.incrementAndGet();
        status.setText("Анализ Java-классов и вызовов…");
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Readable Call Graph: scanning source", true) {
            private GraphModel scanned;
            @Override public void run(@NotNull ProgressIndicator indicator) {
                scanned = ReadAction.compute(() -> JavaCallScanner.scan(project, indicator));
            }
            @Override public void onSuccess() {
                if (request != scanGeneration.get() || project.isDisposed()) return;
                model = scanned;
                updateChoices();
                refresh.setEnabled(true);
                rebuild();
            }
            @Override public void onCancel() {
                if (request == scanGeneration.get()) { refresh.setEnabled(true); status.setText("Анализ отменён."); }
            }
            @Override public void onThrowable(@NotNull Throwable e) {
                if (request == scanGeneration.get()) {
                    refresh.setEnabled(true);
                    status.setText("Ошибка анализа: " + e.getMessage());
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
            chooser.removeAllItems();
            for (GraphModel.ClassNode c : values) chooser.addItem(new Choice(c.id(), c.displayName()));
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
        if (model.classes.isEmpty()) {
            canvas.setLayoutResult(new ReadableLayout().layout(GraphModel.empty(), spacing.getValue()));
            return;
        }
        String id = selectedId();
        if (id == null) return;
        final boolean useFocus = focusOnly.isSelected();
        final GraphModel slice = useFocus ? focused(id) : overview();
        int generationId = layoutGeneration.incrementAndGet();
        final int currentSpacing = spacing.getValue();
        status.setText("Раскладка: " + slice.classes.size() + " классов, " + slice.edges.size() + " связей…");
        SwingWorker<ReadableLayout.Result, Void> worker = new SwingWorker<>() {
            @Override protected ReadableLayout.Result doInBackground() {
                ReadableLayout engine = new ReadableLayout();
                return useFocus ? engine.layoutFocus(slice, id, currentSpacing)
                        : engine.layout(slice, currentSpacing);
            }
            @Override protected void done() {
                if (generationId != layoutGeneration.get() || project.isDisposed()) return;
                try {
                    canvas.setLayoutResult(get());
                    canvas.setSelectedClass(id);
                    // Keep method names at readable size; scroll the focused class into view.
                    canvas.setZoom(1.0);
                    var focusCard = canvas.getLayoutResult().classes().get(id);
                    if (focusCard != null) {
                        Dimension area = scroll.getViewport().getExtentSize();
                        int x = (int) Math.max(0, focusCard.bounds().getCenterX() - area.width * .35);
                        int y = (int) Math.max(0, focusCard.bounds().getCenterY() - area.height * .5);
                        scroll.getViewport().setViewPosition(new Point(x, y));
                    }
                    status.setText("Проект: " + model.classes.size() + " классов, " + model.edges.size()
                            + " связей | На схеме: " + slice.classes.size() + " классов, " + slice.edges.size()
                            + " стрелок | Ctrl+колесо: масштаб | двойной щелчок: код");
                } catch (Exception ex) {
                    status.setText("Ошибка раскладки: " + ex.getMessage());
                }
            }
        };
        worker.execute();
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
        for (String n : neighbors) {
            if (keep.size() >= 19) break;
            keep.add(n);
        }
        GraphModel slice = model.subgraph(keep);
        ArrayList<GraphModel.CallEdge> edges = new ArrayList<>();
        for (GraphModel.CallEdge e : slice.edges)
            if (outward ? e.fromClass().equals(id) : e.toClass().equals(id)) edges.add(e);
        edges.sort(Comparator.<GraphModel.CallEdge>comparingInt(e -> -e.count())
                .thenComparing(GraphModel.CallEdge::methodId));
        if (edges.size() > 65) edges = new ArrayList<>(edges.subList(0, 65));
        return new GraphModel(slice.classes.values(), edges);
    }

    private GraphModel overview() {
        // Very large project view otherwise becomes an unreadable mesh.
        HashMap<String, Integer> activity = new HashMap<>();
        for (GraphModel.CallEdge e : model.edges) {
            activity.merge(e.fromClass(), e.count(), Integer::sum);
            activity.merge(e.toClass(), e.count(), Integer::sum);
        }
        ArrayList<String> names = new ArrayList<>(model.classes.keySet());
        names.sort(Comparator.<String>comparingInt(id -> -activity.getOrDefault(id, 0)).thenComparing(id -> id));
        Set<String> keep = new LinkedHashSet<>(names.subList(0, Math.min(names.size(), 60)));
        GraphModel slice = model.subgraph(keep);
        ArrayList<GraphModel.CallEdge> edges = new ArrayList<>(slice.edges);
        edges.sort(Comparator.<GraphModel.CallEdge>comparingInt(e -> -e.count())
                .thenComparing(GraphModel.CallEdge::methodId));
        if (edges.size() > 130) edges = new ArrayList<>(edges.subList(0, 130));
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
