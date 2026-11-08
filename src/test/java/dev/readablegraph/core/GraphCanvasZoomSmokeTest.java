package dev.readablegraph.core;

import dev.readablegraph.ui.GraphCanvas;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.Map;

/** Smoke test: wheel zoom keeps the source point beneath the cursor stable. */
public final class GraphCanvasZoomSmokeTest {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            GraphCanvas canvas = new GraphCanvas((id, method) -> {});
            canvas.setLayoutResult(new ReadableLayout.Result(Map.of(), java.util.List.of(), 4000, 3000));
            JScrollPane scroll = new JScrollPane(canvas);
            scroll.setSize(600, 450);
            scroll.doLayout();
            JViewport viewport = scroll.getViewport();
            canvas.setSize(canvas.getPreferredSize());
            viewport.setViewPosition(new Point(350, 270));

            int pointerOnCanvasX = 350 + 210;
            int pointerOnCanvasY = 270 + 170;
            double beforeX = pointerOnCanvasX / canvas.getZoom();
            double beforeY = pointerOnCanvasY / canvas.getZoom();
            MouseWheelEvent event = new MouseWheelEvent(canvas, MouseEvent.MOUSE_WHEEL,
                    System.currentTimeMillis(), 0, pointerOnCanvasX, pointerOnCanvasY,
                    0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -3);
            canvas.dispatchEvent(event);
            if (!event.isConsumed()) throw new AssertionError("Mouse wheel was not handled");
            if (canvas.getZoom() <= 1.0) throw new AssertionError("Wheel up did not zoom in");
            Point pos = viewport.getViewPosition();
            double afterX = (pos.x + 210) / canvas.getZoom();
            double afterY = (pos.y + 170) / canvas.getZoom();
            if (Math.abs(beforeX - afterX) > 2 || Math.abs(beforeY - afterY) > 2)
                throw new AssertionError("Cursor anchor moved: " + beforeX + "," + beforeY
                        + " -> " + afterX + "," + afterY);

            canvas.setZoom(0.00001);
            if (Math.abs(canvas.getZoom() - GraphCanvas.MIN_ZOOM) > 0.0000001)
                throw new AssertionError("Min zoom clamp failed");
            canvas.fitTo(new Dimension(600, 450));
            if (canvas.getZoom() < GraphCanvas.MIN_ZOOM || canvas.getZoom() > GraphCanvas.MAX_ZOOM)
                throw new AssertionError("Fit zoom out of range");
            System.out.println("OK: wheel zoom, cursor anchoring, min zoom and fit");
        });
    }
}
