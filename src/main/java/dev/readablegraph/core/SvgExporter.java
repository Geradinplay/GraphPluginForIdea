package dev.readablegraph.core;

import java.awt.geom.*;
import java.util.*;

/** Zoom-independent SVG export; the graphic can be opened in a browser or Inkscape. */
public final class SvgExporter {
    private SvgExporter() {}
    public static String export(ReadableLayout.Result layout) {
        StringBuilder s = new StringBuilder(16_384);
        s.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        s.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"")
         .append((int) Math.ceil(layout.width())).append("\" height=\"")
         .append((int) Math.ceil(layout.height())).append("\" viewBox=\"0 0 ")
         .append((int) Math.ceil(layout.width())).append(" ")
         .append((int) Math.ceil(layout.height())).append("\">\n");
        s.append("<defs><marker id=\"arrow\" markerWidth=\"9\" markerHeight=\"9\" refX=\"8\" refY=\"4.5\" orient=\"auto-start-reverse\"><path d=\"M 0 0 L 9 4.5 L 0 9 Z\" fill=\"#456da8\"/></marker></defs>\n");
        s.append("<rect width=\"100%\" height=\"100%\" fill=\"#f7f9fc\"/>\n");
        for (ReadableLayout.RoutedEdge edge : layout.edges()) {
            s.append("<polyline fill=\"none\" stroke=\"#456da8\" stroke-width=\"1.9\" stroke-linecap=\"round\" stroke-linejoin=\"round\" marker-end=\"url(#arrow)\" points=\"");
            for (Point2D.Double p : edge.path()) s.append((int) Math.round(p.x)).append(',').append((int) Math.round(p.y)).append(' ');
            s.append("\"><title>").append(esc(edge.edge().methodId())).append(" → ")
             .append(esc(edge.edge().toClass())).append(" (x").append(edge.edge().count()).append(")</title></polyline>\n");
        }
        for (ReadableLayout.PlacedClass pc : layout.classes().values()) {
            Rectangle2D.Double b = pc.bounds();
            GraphModel.ClassNode c = pc.node();
            s.append("<g><title>").append(esc(c.id())).append("</title><rect x=\"")
             .append((int)b.x).append("\" y=\"").append((int)b.y)
             .append("\" width=\"330\" height=\"").append((int)b.height)
             .append("\" rx=\"12\" fill=\"white\" stroke=\"#b7c5d8\"/>\n");
            s.append("<rect x=\"").append((int)b.x + 1).append("\" y=\"").append((int)b.y + 1)
             .append("\" width=\"328\" height=\"52\" rx=\"11\" fill=\"#eff4fa\"/>\n");
            s.append(text(b.x + 14, b.y + 24, c.displayName(), 15, "bold", "#263040"));
            s.append(text(b.x + 14, b.y + 42, c.packageName(), 11, "normal", "#6f7a8b"));
            for (int i = 0; i < c.methods().size(); i++) {
                double y = b.y + 53 + i * 29;
                if (i > 0) s.append("<line x1=\"").append((int)b.x + 9)
                    .append("\" y1=\"").append((int)y).append("\" x2=\"")
                    .append((int)b.getMaxX() - 9).append("\" y2=\"").append((int)y)
                    .append("\" stroke=\"#e2e7ee\"/>");
                s.append(text(b.x + 16, y + 19, c.methods().get(i).label(), 13, "normal", "#263040"));
            }
            s.append("</g>\n");
        }
        return s.append("</svg>\n").toString();
    }
    private static String text(double x, double y, String t, int size, String weight, String color) {
        return "<text x=\"" + (int)x + "\" y=\"" + (int)y + "\" font-family=\"Arial, sans-serif\" font-size=\"" + size + "\" font-weight=\"" + weight + "\" fill=\"" + color + "\"><title>" + esc(t) + "</title>" + esc(t) + "</text>\n";
    }
    private static String esc(String t) {
        return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
}
