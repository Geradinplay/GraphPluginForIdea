package dev.readablegraph.core;

import dev.readablegraph.ui.GraphCanvas;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/** Standalone smoke tests: javac -cp /tmp/rcg-classes -d /tmp/rcg-classes ...; java ... */
public class ReadableLayoutSmokeTest {
    static GraphModel.ClassNode cls(String id, int methodCount) {
        List<GraphModel.MethodNode> methods = new ArrayList<>();
        for (int i = 0; i < methodCount; i++)
            methods.add(new GraphModel.MethodNode(id + "#action" + i + "()", "action" + i + "(String, int)", i * 60));
        return new GraphModel.ClassNode(id, id.substring(id.lastIndexOf('.') + 1),
                id.substring(0, id.lastIndexOf('.')), methods);
    }
    private static void checkReadableGeometry(ReadableLayout.Result result) {
        var paths = result.edges();
        for (int i = 0; i < paths.size(); i++) for (int j = i + 1; j < paths.size(); j++) {
            var left = paths.get(i).path(); var right = paths.get(j).path();
            for (int a = 1; a < left.size(); a++) for (int b = 1; b < right.size(); b++) {
                var p = left.get(a - 1); var q = left.get(a);
                var r = right.get(b - 1); var t = right.get(b);
                boolean v1 = Math.abs(p.x - q.x) < .001, v2 = Math.abs(r.x - t.x) < .001;
                if (v1 != v2) {
                    var v0 = v1 ? p : r; var vEnd = v1 ? q : t;
                    var h0 = v1 ? r : p; var hEnd = v1 ? t : q;
                    if (v0.x > Math.min(h0.x, hEnd.x) && v0.x < Math.max(h0.x, hEnd.x)
                        && h0.y > Math.min(v0.y, vEnd.y) && h0.y < Math.max(v0.y, vEnd.y))
                        throw new AssertionError("Intersecting edges: " + i + " and " + j);
                } else if (v1 && Math.abs(p.x - r.x) < .001) {
                    if (Math.min(Math.max(p.y, q.y), Math.max(r.y, t.y))
                        - Math.max(Math.min(p.y, q.y), Math.min(r.y, t.y)) > 1)
                        throw new AssertionError("Overlapping vertical lines: " + i + " and " + j);
                } else if (!v1 && Math.abs(p.y - r.y) < .001) {
                    if (Math.min(Math.max(p.x, q.x), Math.max(r.x, t.x))
                        - Math.max(Math.min(p.x, q.x), Math.min(r.x, t.x)) > 1)
                        throw new AssertionError("Overlapping horizontal lines: " + i + " and " + j);
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        List<GraphModel.ClassNode> nodes = List.of(cls("example.Orchestrator", 5),
                cls("example.ChatPage", 3), cls("example.MemoryService", 4),
                cls("example.WebSearch", 3), cls("example.LlmServer", 4),
                cls("example.ModelManager", 3));
        List<GraphModel.CallEdge> edges = new ArrayList<>();
        edges.add(new GraphModel.CallEdge("example.Orchestrator", "example.Orchestrator#action0()", "example.ChatPage", 3));
        edges.add(new GraphModel.CallEdge("example.Orchestrator", "example.Orchestrator#action1()", "example.LlmServer", 2));
        edges.add(new GraphModel.CallEdge("example.Orchestrator", "example.Orchestrator#action2()", "example.ModelManager", 1));
        edges.add(new GraphModel.CallEdge("example.Orchestrator", "example.Orchestrator#action3()", "example.WebSearch", 6));
        edges.add(new GraphModel.CallEdge("example.Orchestrator", "example.Orchestrator#action4()", "example.MemoryService", 1));
        // incoming calls intentionally separated to avoid a two-way mesh



        GraphModel m = new GraphModel(nodes, edges);
        ReadableLayout l = new ReadableLayout();
        var res = l.layoutFocus(m, "example.Orchestrator", 220);
        for (var a : res.classes().values()) for (var b : res.classes().values()) {
            if (a == b) continue;
            if (a.bounds().intersects(b.bounds())) throw new AssertionError("Overlapping cards");
        }
        if (res.edges().size() != edges.size()) throw new AssertionError("Missing edges");
        checkReadableGeometry(res);
        String svg = SvgExporter.export(res);
        if (!svg.contains("Orchestrator") || !svg.contains("marker-end")) throw new AssertionError("Invalid SVG");
        Path preview = Path.of(System.getProperty("rcg.preview.dir", "target/preview"));
        Files.createDirectories(preview);
        Path output = preview.resolve("example-preview.svg");
        Files.writeString(output, svg);
        GraphCanvas canvas = new GraphCanvas((c, v) -> {});
        canvas.setLayoutResult(res);
        canvas.setZoom(.70);
        canvas.setSize(canvas.getPreferredSize());
        BufferedImage img = new BufferedImage(canvas.getWidth(), canvas.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        canvas.paint(g);
        g.dispose();
        ImageIO.write(img, "png", preview.resolve("example-preview.png").toFile());
        System.out.println("OK: " + res.classes().size() + " cards, " + res.edges().size() + " links, " + res.width() + "x" + res.height());
    }
}
