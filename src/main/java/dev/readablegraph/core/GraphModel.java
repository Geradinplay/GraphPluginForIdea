package dev.readablegraph.core;

import java.util.*;

/** Plain immutable data: edge means declared method -> class from which it was called. */
public final class GraphModel {
    public final Map<String, ClassNode> classes;
    public final List<CallEdge> edges;

    public GraphModel(Collection<ClassNode> nodes, Collection<CallEdge> edges) {
        LinkedHashMap<String, ClassNode> m = new LinkedHashMap<>();
        for (ClassNode node : nodes) m.put(node.id, node);
        this.classes = Collections.unmodifiableMap(m);
        this.edges = List.copyOf(edges);
    }

    public static GraphModel empty() { return new GraphModel(List.of(), List.of()); }

    public GraphModel subgraph(Set<String> ids) {
        ArrayList<ClassNode> n = new ArrayList<>();
        ArrayList<CallEdge> e = new ArrayList<>();
        for (ClassNode node : classes.values()) if (ids.contains(node.id)) n.add(node);
        for (CallEdge edge : edges)
            if (ids.contains(edge.fromClass) && ids.contains(edge.toClass)) e.add(edge);
        return new GraphModel(n, e);
    }

    public record ClassNode(String id, String displayName, String packageName, List<MethodNode> methods) {
        public ClassNode { methods = List.copyOf(methods); }
    }

    public record MethodNode(String id, String label, int navigationOffset) {}
    public record CallEdge(String fromClass, String methodId, String toClass, int count) {}
}
