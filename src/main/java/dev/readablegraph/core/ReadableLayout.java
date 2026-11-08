package dev.readablegraph.core;

import java.awt.geom.*;
import java.util.*;

/**
 * Spacious layered class placement + orthogonal routed calls.
 * Uses obstacle-aware A* and crossing/edge-sharing penalties. Absolute absence
 * of crossings is impossible for arbitrary non-planar call graphs.
 */
public final class ReadableLayout {
    private static final int WIDTH = 330;
    private static final int HEADER = 53;
    private static final int ROW = 29;
    private static final int FOOTER = 13;
    private static final int GRID = 20;
    private static final int MARGIN = 100;

    public record PlacedClass(GraphModel.ClassNode node, Rectangle2D.Double bounds) {}
    public record RoutedEdge(GraphModel.CallEdge edge, List<Point2D.Double> path) {}
    public record Result(Map<String, PlacedClass> classes, List<RoutedEdge> edges,
                         double width, double height) {}
    private record Cell(int x, int y) {}
    private record State(int x, int y, int dir) {}
    private record Step(State state, double cost, double estimate) {}
    private record Segment(Cell a, Cell b) {
        static Segment of(Cell a, Cell b) {
            if (a.y < b.y || (a.y == b.y && a.x <= b.x)) return new Segment(a, b);
            return new Segment(b, a);
        }
    }

    public Result layout(GraphModel model, int spacing) {
        if (model.classes.isEmpty()) return new Result(Map.of(), List.of(), 800, 500);
        if (model.classes.size() > 24 || model.edges.size() > 40) return layoutMassive(model, spacing);
        int gapX = 220 + spacing;
        int gapY = 85 + spacing / 2;
        Map<String, Set<String>> neighbors = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        for (String id : model.classes.keySet()) { neighbors.put(id, new HashSet<>()); indegree.put(id, 0); }
        for (GraphModel.CallEdge e : model.edges) {
            if (neighbors.containsKey(e.fromClass()) && neighbors.containsKey(e.toClass())
                    && !e.fromClass().equals(e.toClass())) {
                neighbors.get(e.fromClass()).add(e.toClass());
                neighbors.get(e.toClass()).add(e.fromClass());
                indegree.merge(e.toClass(), 1, Integer::sum);
            }
        }
        // Disconnected components are deliberately kept separated.
        ArrayList<String> roots = new ArrayList<>(model.classes.keySet());
        roots.sort(Comparator.<String>comparingInt(id -> indegree.getOrDefault(id, 0))
                .thenComparing((a, b) -> Integer.compare(neighbors.get(b).size(), neighbors.get(a).size()))
                .thenComparing(s -> s));
        Map<String, Integer> depth = new HashMap<>();
        int component = 0;
        HashMap<String, Integer> group = new HashMap<>();
        for (String root : roots) {
            if (depth.containsKey(root)) continue;
            ArrayDeque<String> q = new ArrayDeque<>();
            q.add(root);
            depth.put(root, 0); group.put(root, component);
            while (!q.isEmpty()) {
                String cur = q.removeFirst();
                ArrayList<String> ns = new ArrayList<>(neighbors.get(cur));
                Collections.sort(ns);
                for (String next : ns) if (!depth.containsKey(next)) {
                    depth.put(next, depth.get(cur) + 1);
                    group.put(next, component);
                    q.add(next);
                }
            }
            component++;
        }
        // We cap column depth so large chains do not run off to the horizon.
        NavigableMap<Integer, List<String>> layers = new TreeMap<>();
        for (String id : model.classes.keySet()) {
            int layer = Math.min(depth.getOrDefault(id, 0), 4);
            layers.computeIfAbsent(layer, k -> new ArrayList<>()).add(id);
        }
        Comparator<String> initial = Comparator.comparingInt((String s) -> group.getOrDefault(s, 0))
                .thenComparing(s -> s);
        for (List<String> layer : layers.values()) layer.sort(initial);
        // Barycentric cross-reduction passes.
        for (int pass = 0; pass < 8; pass++) {
            boolean reverse = pass % 2 == 1;
            List<Integer> keys = new ArrayList<>(layers.keySet());
            if (reverse) Collections.reverse(keys);
            for (int k : keys) {
                List<String> row = layers.get(k);
                Map<String, Double> positions = new HashMap<>();
                for (Map.Entry<Integer, List<String>> ent : layers.entrySet()) {
                    for (int i = 0; i < ent.getValue().size(); i++)
                        positions.put(ent.getValue().get(i), (double) (i * 100 + ent.getKey() * 15));
                }
                row.sort(Comparator.<String>comparingInt(id -> group.getOrDefault(id, 0))
                    .thenComparingDouble(id -> {
                        double sum = 0; int n = 0;
                        for (String neighbor : neighbors.get(id)) {
                            if (positions.containsKey(neighbor)) { sum += positions.get(neighbor); n++; }
                        }
                        return n == 0 ? positions.get(id) : sum / n;
                    }).thenComparing(id -> id));
            }
        }
        LinkedHashMap<String, PlacedClass> placed = new LinkedHashMap<>();
        double maxH = 0;
        for (var ent : layers.entrySet()) {
            double height = 0;
            for (String id : ent.getValue())
                height += boxHeight(model.classes.get(id)) + gapY;
            maxH = Math.max(maxH, height);
        }
        for (var ent : layers.entrySet()) {
            double h = 0;
            for (String id : ent.getValue()) h += boxHeight(model.classes.get(id)) + gapY;
            double y = MARGIN + (maxH - h) / 2;
            for (String id : ent.getValue()) {
                GraphModel.ClassNode node = model.classes.get(id);
                double bh = boxHeight(node);
                Rectangle2D.Double b = new Rectangle2D.Double(MARGIN + ent.getKey() * (WIDTH + gapX), y, WIDTH, bh);
                placed.put(id, new PlacedClass(node, b));
                y += bh + gapY;
            }
        }
        double width = MARGIN * 2 + (layers.lastKey() + 1) * WIDTH + layers.lastKey() * gapX;
        double height = maxH + MARGIN * 2;
        return new Result(Collections.unmodifiableMap(placed),
                routeEdges(model.edges, placed, width, height), width, height);
    }

    /** Focus topology: incoming callers on the left, selected class in the center,
     * consumers on the right; isolated neighbors are intentionally absent. */
    public Result layoutFocus(GraphModel model, String focusId, int spacing) {
        GraphModel.ClassNode focus = model.classes.get(focusId);
        if (focus == null) return layout(model, spacing);
        if (model.classes.size() > 24 || model.edges.size() > 40) return layoutMassive(model, spacing);
        int gapX = 220 + spacing;
        int gapY = 85 + spacing / 2;
        Map<String, Integer> leftScore = new HashMap<>(), rightScore = new HashMap<>();
        Map<String, Integer> anchor = new HashMap<>();
        for (GraphModel.CallEdge e : model.edges) {
            if (focusId.equals(e.fromClass()) && !focusId.equals(e.toClass())) {
                rightScore.merge(e.toClass(), 1, Integer::sum);
                int mi = methodIndex(focus, e.methodId());
                anchor.merge(e.toClass(), mi, Math::min);
            }
            if (focusId.equals(e.toClass()) && !focusId.equals(e.fromClass()))
                leftScore.merge(e.fromClass(), 1, Integer::sum);
        }
        List<String> left = new ArrayList<>(), right = new ArrayList<>();
        for (String id : model.classes.keySet()) {
            if (id.equals(focusId)) continue;
            if (leftScore.getOrDefault(id, 0) > rightScore.getOrDefault(id, 0)) left.add(id);
            else right.add(id);
        }
        left.sort(Comparator.comparing(s -> s));
        right.sort(Comparator.<String>comparingInt(s -> anchor.getOrDefault(s, Integer.MAX_VALUE))
                .thenComparing(s -> s));
        double leftH = columnHeight(left, model, gapY);
        double rightH = columnHeight(right, model, gapY);
        double mainH = boxHeight(focus);
        double usable = Math.max(mainH, Math.max(leftH, rightH));
        boolean hasLeft = !left.isEmpty(), hasRight = !right.isEmpty();
        int columnCount = 1 + (hasLeft ? 1 : 0) + (hasRight ? 1 : 0);
        double width = MARGIN * 2 + WIDTH * columnCount + gapX * (columnCount - 1);
        double height = usable + MARGIN * 2;
        LinkedHashMap<String, PlacedClass> placed = new LinkedHashMap<>();
        double focusX = MARGIN + (hasLeft ? WIDTH + gapX : 0);
        if (hasLeft) placeColumn(left, model, placed, MARGIN,
                MARGIN + (usable - leftH) / 2, gapY);
        placed.put(focusId, new PlacedClass(focus, new Rectangle2D.Double(
                focusX, MARGIN + (usable - mainH) / 2, WIDTH, mainH)));
        if (hasRight) placeColumn(right, model, placed, focusX + WIDTH + gapX,
                MARGIN + (usable - rightH) / 2, gapY);
        return new Result(Collections.unmodifiableMap(placed),
                routeEdges(model.edges, placed, width, height), width, height);
    }

    /** Linear-memory layout for very large projects. All classes and all edges are retained.
     * Routes use gutters beside columns and shared free corridors between rows, rather
     * than allocating an A* obstacle grid for every edge. Crossing-free drawings cannot
     * be guaranteed for arbitrary non-planar graphs. */
    private Result layoutMassive(GraphModel model, int spacing) {
        ArrayList<GraphModel.ClassNode> nodes = new ArrayList<>(model.classes.values());
        nodes.sort(Comparator.comparing(GraphModel.ClassNode::packageName, Comparator.nullsFirst(String::compareTo))
                .thenComparing(GraphModel.ClassNode::id));
        int count = nodes.size();
        int cols = count >= 500 ? 12 : (count >= 150 ? 8 : 5);
        cols = Math.min(count, cols);
        int rows = (count + cols - 1) / cols;
        double gapX = 260 + spacing;
        double gapY = 160 + spacing / 2.0;
        double[] heights = new double[rows];
        for (int i = 0; i < count; i++) {
            heights[i / cols] = Math.max(heights[i / cols], boxHeight(nodes.get(i)));
        }
        double[] ys = new double[rows];
        double y = MARGIN + 30;
        for (int r = 0; r < rows; r++) { ys[r] = y; y += heights[r] + gapY; }
        double totalWidth = MARGIN * 2 + cols * WIDTH + (cols - 1) * gapX + 100;
        double totalHeight = y + MARGIN;
        LinkedHashMap<String, PlacedClass> placed = new LinkedHashMap<>();
        HashMap<String, Integer> rowOf = new HashMap<>(count * 2);
        HashMap<String, Map<String, Integer>> methodRows = new HashMap<>(count * 2);
        for (int i = 0; i < count; i++) {
            if ((i & 255) == 0 && Thread.currentThread().isInterrupted())
                throw new java.util.concurrent.CancellationException("Раскладка отменена");
            GraphModel.ClassNode node = nodes.get(i);
            int row = i / cols, col = i % cols;
            double x = MARGIN + col * (WIDTH + gapX);
            placed.put(node.id(), new PlacedClass(node, new Rectangle2D.Double(x, ys[row], WIDTH, boxHeight(node))));
            rowOf.put(node.id(), row);
            HashMap<String, Integer> positions = new HashMap<>();
            for (int mi = 0; mi < node.methods().size(); mi++) positions.put(node.methods().get(mi).id(), mi);
            methodRows.put(node.id(), positions);
        }
        HashMap<String, Integer> incomingTotal = new HashMap<>();
        for (GraphModel.CallEdge edge : model.edges) incomingTotal.merge(edge.toClass(), 1, Integer::sum);
        HashMap<String, Integer> incomingSeen = new HashMap<>();
        HashMap<String, Integer> outgoingSeen = new HashMap<>();
        ArrayList<RoutedEdge> routes = new ArrayList<>(model.edges.size());
        int ordinal = 0;
        for (GraphModel.CallEdge edge : model.edges) {
            if ((ordinal++ & 255) == 0 && Thread.currentThread().isInterrupted())
                throw new java.util.concurrent.CancellationException("Раскладка отменена");
            PlacedClass from = placed.get(edge.fromClass()), to = placed.get(edge.toClass());
            if (from == null || to == null) continue;
            Rectangle2D.Double a = from.bounds(), b = to.bounds();
            int method = methodRows.get(edge.fromClass()).getOrDefault(edge.methodId(), 0);
            double sy = a.y + HEADER + (method + .5) * ROW;
            int fanIndex = outgoingSeen.merge(edge.fromClass() + "\0" + edge.methodId(), 1, Integer::sum) - 1;
            int ti = incomingSeen.merge(edge.toClass(), 1, Integer::sum);
            int total = incomingTotal.getOrDefault(edge.toClass(), 1);
            double ty = b.y + 18 + (b.height - 36) * ti / (double)(total + 1);
            int targetRow = rowOf.get(edge.toClass());
            // Horizontal runs are inside a corridor shared by all columns,
            // so they never cut through class cards.
            double corridor = ys[targetRow] - gapY / 2.0;
            double lane = ((ordinal * 13L) % Math.max(1L, (long)(gapY / 7) - 2)) * 7.0;
            double roadY = Math.max(MARGIN + 4, corridor - gapY / 4 + lane);
            double rightGutter = a.getMaxX() + 32 + (fanIndex % 11) * 8;
            double leftGutter = b.x - 32 - ((ti - 1) % 11) * 8;
            List<Point2D.Double> path = simplify(List.of(
                    new Point2D.Double(a.getMaxX(), sy),
                    new Point2D.Double(rightGutter, sy),
                    new Point2D.Double(rightGutter, roadY),
                    new Point2D.Double(leftGutter, roadY),
                    new Point2D.Double(leftGutter, ty),
                    new Point2D.Double(b.x, ty)));
            routes.add(new RoutedEdge(edge, path));
        }
        return new Result(Collections.unmodifiableMap(placed), List.copyOf(routes), totalWidth, totalHeight);
    }

    private static int methodIndex(GraphModel.ClassNode node, String methodId) {
        for (int i = 0; i < node.methods().size(); i++)
            if (node.methods().get(i).id().equals(methodId)) return i;
        return Integer.MAX_VALUE;
    }

    private static double columnHeight(List<String> ids, GraphModel model, int gapY) {
        double h = 0;
        for (String id : ids) h += boxHeight(model.classes.get(id)) + gapY;
        return ids.isEmpty() ? 0 : h - gapY;
    }

    private static void placeColumn(List<String> ids, GraphModel model,
                                    Map<String, PlacedClass> placed, double x, double y, int gapY) {
        for (String id : ids) {
            GraphModel.ClassNode node = model.classes.get(id);
            double h = boxHeight(node);
            placed.put(id, new PlacedClass(node, new Rectangle2D.Double(x, y, WIDTH, h)));
            y += h + gapY;
        }
    }

    private static double boxHeight(GraphModel.ClassNode n) {
        return HEADER + Math.max(1, n.methods().size()) * ROW + FOOTER;
    }

    private static List<RoutedEdge> routeEdges(List<GraphModel.CallEdge> edges,
                                                Map<String, PlacedClass> boxes,
                                                double width, double height) {
        ArrayList<GraphModel.CallEdge> sorted = new ArrayList<>(edges);
        sorted.removeIf(e -> !boxes.containsKey(e.fromClass()) || !boxes.containsKey(e.toClass()));
        sorted.sort(Comparator.comparing(GraphModel.CallEdge::fromClass)
                .thenComparing(GraphModel.CallEdge::methodId).thenComparing(GraphModel.CallEdge::toClass));
        Map<String, List<GraphModel.CallEdge>> out = new HashMap<>();
        Map<String, List<GraphModel.CallEdge>> in = new HashMap<>();
        for (GraphModel.CallEdge e : sorted) {
            out.computeIfAbsent(e.fromClass() + "|" + e.methodId(), k -> new ArrayList<>()).add(e);
            in.computeIfAbsent(e.toClass(), k -> new ArrayList<>()).add(e);
        }
        Map<Segment, Integer> used = new HashMap<>();
        ArrayList<RoutedEdge> result = new ArrayList<>();
        for (GraphModel.CallEdge e : sorted) {
            Rectangle2D.Double a = boxes.get(e.fromClass()).bounds();
            Rectangle2D.Double b = boxes.get(e.toClass()).bounds();
            List<GraphModel.MethodNode> methods = boxes.get(e.fromClass()).node().methods();
            int methodIndex = 0;
            for (int i = 0; i < methods.size(); i++) {
                if (methods.get(i).id().equals(e.methodId())) { methodIndex = i; break; }
            }
            List<GraphModel.CallEdge> fan = out.get(e.fromClass() + "|" + e.methodId());
            int fi = fan.indexOf(e);
            double sy = a.y + HEADER + methodIndex * ROW + ROW / 2.0
                    + (fi - (fan.size() - 1) / 2.0) * Math.min(5.5, ROW / (fan.size() + 1.0));
            List<GraphModel.CallEdge> incoming = in.get(e.toClass());
            int ti = incoming.indexOf(e);
            double ty = b.y + 25 + (b.height - 40) * (ti + 1.0) / (incoming.size() + 1.0);
            // Self-calls get an external loop instead of crossing the node.
            boolean self = e.fromClass().equals(e.toClass());
            int ax = grid(a.getMaxX() + 42 + Math.min(fi, 8) * 15), ay = grid(sy);
            int laneShift = Math.min(ti, 8) * 15;
            // Pick a distinct approach lane. If a route collides with previous
            // routes, retry nearby lanes before keeping the least-conflicting one.
            int[] alternatives = {0, 20, -20, 40, -40, 60, -60};
            List<Cell> chosenCells = List.of();
            List<Point2D.Double> chosenPath = List.of();
            double bestScore = Double.POSITIVE_INFINITY;
            for (int offset : alternatives) {
                int bx = grid(self ? b.getMaxX() + 42 + laneShift - offset
                        : b.x - 42 - laneShift + offset), by = grid(ty);
                if (bx < 0 || bx > grid(width) + 7) continue;
                // Ports must stay outside the target card.
                if (!self && bx * GRID >= b.x - 12) continue;
                List<Cell> cells = findRoute(new Cell(ax, ay), new Cell(bx, by), boxes.values(), used,
                        width, height, self);
                ArrayList<Point2D.Double> raw = new ArrayList<>();
                raw.add(new Point2D.Double(a.getMaxX(), sy));
                raw.add(new Point2D.Double(ax * GRID, sy));
                raw.add(new Point2D.Double(ax * GRID, ay * GRID));
                for (Cell c : cells) raw.add(new Point2D.Double(c.x * GRID, c.y * GRID));
                raw.add(new Point2D.Double(bx * GRID, by * GRID));
                raw.add(new Point2D.Double(bx * GRID, ty));
                raw.add(new Point2D.Double(self ? b.getMaxX() : b.x, ty));
                List<Point2D.Double> path = simplify(raw);
                int penalty = collisionPenalty(path, result);
                double length = 0;
                for (int i = 1; i < path.size(); i++) length += path.get(i).distance(path.get(i - 1));
                double score = penalty * 1000000.0 + length;
                if (score < bestScore) {
                    bestScore = score;
                    chosenCells = cells;
                    chosenPath = path;
                }
                if (penalty == 0) break;
            }
            result.add(new RoutedEdge(e, chosenPath));
            for (int i = 1; i < chosenCells.size(); i++)
                used.merge(Segment.of(chosenCells.get(i - 1), chosenCells.get(i)), 1, Integer::sum);
        }
        return List.copyOf(result);
    }

    /** Counts proper crossings and shared-line lengths against previously routed paths. */
    private static int collisionPenalty(List<Point2D.Double> candidate, List<RoutedEdge> previous) {
        int total = 0;
        for (RoutedEdge prev : previous) {
            List<Point2D.Double> old = prev.path();
            for (int i = 1; i < candidate.size(); i++) {
                Point2D.Double a = candidate.get(i - 1), b = candidate.get(i);
                for (int j = 1; j < old.size(); j++) {
                    Point2D.Double c = old.get(j - 1), d = old.get(j);
                    boolean av = Math.abs(a.x - b.x) < .01;
                    boolean bv = Math.abs(c.x - d.x) < .01;
                    if (av != bv) {
                        Point2D.Double v0 = av ? a : c, v1 = av ? b : d;
                        Point2D.Double h0 = av ? c : a, h1 = av ? d : b;
                        if (v0.x > Math.min(h0.x, h1.x) + .01 &&
                            v0.x < Math.max(h0.x, h1.x) - .01 &&
                            h0.y > Math.min(v0.y, v1.y) + .01 &&
                            h0.y < Math.max(v0.y, v1.y) - .01) total += 20;
                    } else if (av) {
                        if (Math.abs(a.x - c.x) < .01) {
                            double overlap = Math.min(Math.max(a.y, b.y), Math.max(c.y, d.y))
                                    - Math.max(Math.min(a.y, b.y), Math.min(c.y, d.y));
                            if (overlap > .5) total += Math.max(1, (int) overlap);
                        }
                    } else if (Math.abs(a.y - c.y) < .01) {
                        double overlap = Math.min(Math.max(a.x, b.x), Math.max(c.x, d.x))
                                - Math.max(Math.min(a.x, b.x), Math.min(c.x, d.x));
                        if (overlap > .5) total += Math.max(1, (int) overlap);
                    }
                }
            }
        }
        return total;
    }

    private static int grid(double v) { return (int) Math.round(v / GRID); }
    private static List<Point2D.Double> simplify(List<Point2D.Double> raw) {
        ArrayList<Point2D.Double> unique = new ArrayList<>();
        for (Point2D.Double p : raw) {
            if (unique.isEmpty() || unique.get(unique.size() - 1).distance(p) > .01) unique.add(p);
        }
        for (int i = 1; i + 1 < unique.size();) {
            Point2D.Double a = unique.get(i - 1), b = unique.get(i), c = unique.get(i + 1);
            if ((Math.abs(a.x - b.x) < .01 && Math.abs(b.x - c.x) < .01) ||
                (Math.abs(a.y - b.y) < .01 && Math.abs(b.y - c.y) < .01)) unique.remove(i);
            else i++;
        }
        return List.copyOf(unique);
    }

    private static List<Cell> findRoute(Cell start, Cell target, Collection<PlacedClass> boxes,
                                        Map<Segment, Integer> used, double w, double h, boolean self) {
        int pad = 7;
        int maxX = grid(w) + pad;
        int maxY = grid(h) + pad;
        boolean[][] blocked = new boolean[maxX + 1][maxY + 1];
        for (PlacedClass node : boxes) {
            Rectangle2D.Double b = node.bounds();
            // Keep at least one clear grid cell outside every card.
            int x0 = Math.max(0, (int) Math.ceil((b.x - 10) / GRID));
            int x1 = Math.min(maxX, (int) Math.floor((b.getMaxX() + 10) / GRID));
            int y0 = Math.max(0, (int) Math.ceil((b.y - 10) / GRID));
            int y1 = Math.min(maxY, (int) Math.floor((b.getMaxY() + 10) / GRID));
            for (int x = x0; x <= x1; x++)
                Arrays.fill(blocked[x], y0, y1 + 1, true);
        }
        blocked[start.x][start.y] = false;
        blocked[target.x][target.y] = false;
        int[] dx = {1, 0, -1, 0}, dy = {0, 1, 0, -1};
        PriorityQueue<Step> pq = new PriorityQueue<>(Comparator.comparingDouble(Step::estimate));
        HashMap<State, Double> best = new HashMap<>();
        HashMap<State, State> parent = new HashMap<>();
        State initial = new State(start.x, start.y, -1);
        pq.add(new Step(initial, 0, manhattan(start, target)));
        best.put(initial, 0.0);
        State goal = null;
        // Prevent runaway layout time on enormous call graphs.
        int explored = 0;
        while (!pq.isEmpty() && explored++ < 160000) {
            Step step = pq.poll();
            State s = step.state;
            if (step.cost > best.getOrDefault(s, Double.MAX_VALUE) + 0.001) continue;
            if (s.x == target.x && s.y == target.y) { goal = s; break; }
            for (int dir = 0; dir < 4; dir++) {
                int nx = s.x + dx[dir], ny = s.y + dy[dir];
                if (nx < 0 || ny < 0 || nx > maxX || ny > maxY || blocked[nx][ny]) continue;
                Cell cur = new Cell(s.x, s.y), next = new Cell(nx, ny);
                int overlap = used.getOrDefault(Segment.of(cur, next), 0);
                int crossings = used.getOrDefault(Segment.of(new Cell(nx, ny - 1), new Cell(nx, ny)), 0)
                              + used.getOrDefault(Segment.of(new Cell(nx, ny), new Cell(nx, ny + 1)), 0)
                              + used.getOrDefault(Segment.of(new Cell(nx - 1, ny), new Cell(nx, ny)), 0)
                              + used.getOrDefault(Segment.of(new Cell(nx, ny), new Cell(nx + 1, ny)), 0);
                double turn = s.dir != -1 && s.dir != dir ? 7.5 : 0;
                double cost = step.cost + 1 + turn + overlap * 28 + (crossings - overlap) * 2.5;
                State n = new State(nx, ny, dir);
                if (cost + .001 < best.getOrDefault(n, Double.MAX_VALUE)) {
                    best.put(n, cost); parent.put(n, s);
                    pq.add(new Step(n, cost, cost + manhattan(next, target)));
                }
            }
        }
        if (goal == null) {
            // Reliable fallback when graph density exceeds the routing budget.
            int lane = self ? Math.max(start.x, target.x) + 4 : Math.max(start.x, target.x) + 4;
            return List.of(start, new Cell(lane, start.y), new Cell(lane, target.y), target);
        }
        ArrayList<Cell> route = new ArrayList<>();
        for (State t = goal; t != null; t = parent.get(t)) route.add(new Cell(t.x, t.y));
        Collections.reverse(route);
        return route;
    }
    private static int manhattan(Cell a, Cell b) { return Math.abs(a.x - b.x) + Math.abs(a.y - b.y); }
}
