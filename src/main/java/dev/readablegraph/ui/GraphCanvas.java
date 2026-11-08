package dev.readablegraph.ui;

import dev.readablegraph.core.GraphModel;
import dev.readablegraph.core.ReadableLayout;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.*;
import java.util.function.BiConsumer;

/** Pure Swing renderer. Mouse: click class, double-click to navigate, wheel to scroll/zoom (Ctrl). */
public final class GraphCanvas extends JComponent {
    private ReadableLayout.Result layout = new ReadableLayout.Result(Map.of(), java.util.List.of(), 800, 500);
    private String selectedClass;
    private double zoom = 1.0;
    private final BiConsumer<String, String> selectionHandler;
    private Point dragPoint;

    private static final Color BACKGROUND = new Color(247, 249, 252);
    private static final Color CARD = Color.WHITE;
    private static final Color BORDER = new Color(183, 197, 216);
    private static final Color EDGE = new Color(69, 109, 168, 190);
    private static final Color SELECTED = new Color(38, 109, 205);
    private static final Color TEXT = new Color(38, 48, 64);
    private static final Color MUTED = new Color(111, 122, 139);

    public GraphCanvas(BiConsumer<String, String> selectionHandler) {
        this.selectionHandler = selectionHandler;
        setOpaque(true);
        setBackground(BACKGROUND);
        setToolTipText(" ");
        setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        setLayoutResult(layout);
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { dragPoint = e.getPoint(); }
            @Override public void mouseClicked(MouseEvent e) {
                Point2D.Double p = at(e.getPoint());
                for (var entry : layout.classes().entrySet()) {
                    if (entry.getValue().bounds().contains(p)) {
                        selectedClass = entry.getKey();
                        repaint();
                        if (e.getClickCount() == 2) {
                            String methodId = methodAt(entry.getValue(), p);
                            selectionHandler.accept(selectedClass, methodId == null ? "@class" : methodId);
                        } else selectionHandler.accept(selectedClass, null);
                        return;
                    }
                }
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                if (dragPoint == null) return;
                Container ancestor = SwingUtilities.getAncestorOfClass(JViewport.class, GraphCanvas.this);
                if (ancestor instanceof JViewport viewport) {
                    Point pos = viewport.getViewPosition();
                    int nx = Math.max(0, Math.min(getWidth() - viewport.getWidth(),
                            pos.x + dragPoint.x - e.getX()));
                    int ny = Math.max(0, Math.min(getHeight() - viewport.getHeight(),
                            pos.y + dragPoint.y - e.getY()));
                    viewport.setViewPosition(new Point(nx, ny));
                }
            }
        });
        addMouseWheelListener(e -> {
            if (e.isControlDown()) {
                setZoom(zoom * Math.pow(1.13, -e.getPreciseWheelRotation()));
                e.consume();
            }
        });
    }

    public void setLayoutResult(ReadableLayout.Result newLayout) {
        this.layout = newLayout;
        refreshSize();
    }
    public ReadableLayout.Result getLayoutResult() { return layout; }
    public void setSelectedClass(String id) { selectedClass = id; repaint(); }
    public String getSelectedClass() { return selectedClass; }
    public void setZoom(double z) { zoom = Math.min(2.5, Math.max(.20, z)); refreshSize(); }
    public double getZoom() { return zoom; }
    public void fitTo(Dimension viewport) {
        if (viewport.width < 1 || viewport.height < 1) return;
        double zx = viewport.width / layout.width();
        double zy = viewport.height / layout.height();
        setZoom(Math.min(zx, zy) * .97);
    }
    private void refreshSize() {
        setPreferredSize(new Dimension((int) Math.ceil(layout.width() * zoom),
                (int) Math.ceil(layout.height() * zoom)));
        revalidate(); repaint();
    }
    private Point2D.Double at(Point p) { return new Point2D.Double(p.x / zoom, p.y / zoom); }
    private static String methodAt(ReadableLayout.PlacedClass c, Point2D p) {
        int row = (int) ((p.getY() - c.bounds().y - 53) / 29);
        if (p.getY() < c.bounds().y + 53 || row < 0 || row >= c.node().methods().size()) return null;
        return c.node().methods().get(row).id();
    }
    @Override public String getToolTipText(MouseEvent e) {
        Point2D p = at(e.getPoint());
        for (var c : layout.classes().values()) {
            if (c.bounds().contains(p)) {
                String method = methodAt(c, p);
                if (method != null) {
                    for (GraphModel.MethodNode m : c.node().methods())
                        if (m.id().equals(method)) return c.node().id() + "#" + m.label() + " (двойной щелчок — перейти)";
                }
                return c.node().id() + " (двойной щелчок — перейти к классу)";
            }
        }
        return "Ctrl+колесо — масштаб; перетаскивание — панорама; двойной щелчок по классу/методу — открыть код";
    }

    @Override protected void paintComponent(Graphics raw) {
        super.paintComponent(raw);
        Graphics2D g = (Graphics2D) raw.create();
        try {
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.scale(zoom, zoom);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            // Edges beneath cards: cards stay readable even when paths are complex.
            for (var routed : layout.edges()) {
                boolean strong = selectedClass == null || selectedClass.equals(routed.edge().fromClass())
                        || selectedClass.equals(routed.edge().toClass());
                g.setColor(strong ? EDGE : new Color(130, 144, 163, 52));
                g.setStroke(new BasicStroke(strong ? 1.9f : 1.2f, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                Path2D p = new Path2D.Double();
                boolean first = true;
                for (Point2D point : routed.path()) {
                    if (first) { p.moveTo(point.getX(), point.getY()); first = false; }
                    else p.lineTo(point.getX(), point.getY());
                }
                g.draw(p);
                drawArrow(g, routed.path());
            }
            for (var placed : layout.classes().values()) drawClass(g, placed);
            if (layout.classes().isEmpty()) {
                g.setFont(getFont().deriveFont(16f));
                g.setColor(MUTED);
                g.drawString("Нажмите «Обновить», чтобы построить связи проекта", 50, 60);
            }
        } finally { g.dispose(); }
    }

    private static void drawArrow(Graphics2D g, java.util.List<Point2D.Double> points) {
        if (points.size() < 2) return;
        Point2D.Double end = points.get(points.size() - 1);
        Point2D.Double prev = points.get(points.size() - 2);
        double a = Math.atan2(end.y - prev.y, end.x - prev.x);
        double size = 11;
        Path2D arrow = new Path2D.Double();
        arrow.moveTo(end.x, end.y);
        arrow.lineTo(end.x - size * Math.cos(a - .48), end.y - size * Math.sin(a - .48));
        arrow.lineTo(end.x - size * Math.cos(a + .48), end.y - size * Math.sin(a + .48));
        arrow.closePath();
        g.fill(arrow);
    }

    private void drawClass(Graphics2D g, ReadableLayout.PlacedClass p) {
        Rectangle2D.Double r = p.bounds();
        boolean selected = p.node().id().equals(selectedClass);
        RoundRectangle2D card = new RoundRectangle2D.Double(r.x, r.y, r.width, r.height, 16, 16);
        g.setColor(new Color(0, 0, 0, 16));
        g.fill(new RoundRectangle2D.Double(r.x + 3, r.y + 5, r.width, r.height, 16, 16));
        g.setColor(CARD); g.fill(card);
        g.setColor(selected ? SELECTED : BORDER);
        g.setStroke(new BasicStroke(selected ? 2.7f : 1.2f)); g.draw(card);
        g.setColor(selected ? new Color(229, 238, 253) : new Color(239, 244, 250));
        g.fill(new RoundRectangle2D.Double(r.x + 2, r.y + 2, r.width - 4, 51, 13, 13));
        g.fill(new Rectangle2D.Double(r.x + 2, r.y + 34, r.width - 4, 19));
        g.setColor(BORDER); g.setStroke(new BasicStroke(1));
        g.draw(new Line2D.Double(r.x, r.y + 53, r.getMaxX(), r.y + 53));
        g.setColor(TEXT); g.setFont(getFont().deriveFont(Font.BOLD, 15f));
        String title = p.node().displayName();
        g.drawString(clipText(g, title, r.width - 25), (int) r.x + 14, (int) r.y + 24);
        g.setColor(MUTED); g.setFont(getFont().deriveFont(11f));
        g.drawString(clipText(g, p.node().packageName(), r.width - 25), (int) r.x + 14, (int) r.y + 42);
        g.setFont(getFont().deriveFont(Font.PLAIN, 13f));
        for (int i = 0; i < p.node().methods().size(); i++) {
            double yy = r.y + 53 + i * 29;
            if (i > 0) {
                g.setColor(new Color(226, 231, 238));
                g.draw(new Line2D.Double(r.x + 9, yy, r.getMaxX() - 9, yy));
            }
            g.setColor(TEXT);
            String label = p.node().methods().get(i).label();
            g.drawString(clipText(g, label, r.width - 34), (int) r.x + 17, (int) yy + 19);
        }
        if (p.node().methods().isEmpty()) {
            g.setColor(MUTED); g.drawString("— нет объявленных методов —", (int) r.x + 15, (int) r.y + 73);
        }
    }
    private static String clipText(Graphics2D g, String text, double width) {
        if (g.getFontMetrics().stringWidth(text) <= width) return text;
        int len = text.length();
        while (len > 1 && g.getFontMetrics().stringWidth(text.substring(0, len) + "…") > width) len--;
        return text.substring(0, len) + "…";
    }
}
