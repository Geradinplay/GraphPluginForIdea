package dev.readablegraph.idea;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import dev.readablegraph.core.GraphModel;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/** Scans Java source PSI, resolves call targets, aggregates method->calling-class edges. */
public final class JavaCallScanner {
    private JavaCallScanner() {}

    public static GraphModel scan(Project project, ProgressIndicator indicator) {
        PsiManager manager = PsiManager.getInstance(project);
        ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
        List<VirtualFile> files = new ArrayList<>(FileTypeIndex.getFiles(
                JavaFileType.INSTANCE, GlobalSearchScope.projectScope(project)));
        files.sort(Comparator.comparing(VirtualFile::getPath));
        LinkedHashMap<String, GraphModel.ClassNode> classes = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> callCounts = new LinkedHashMap<>();
        LinkedHashMap<String, GraphModel.CallEdge> edgePrototypes = new LinkedHashMap<>();

        // Pass 1: every declared class and method, including methods without call sites.
        for (int index = 0; index < files.size(); index++) {
            ProgressManager.checkCanceled();
            VirtualFile v = files.get(index);
            if (!fileIndex.isInSourceContent(v)) continue;
            indicator.setText2("Классы: " + v.getName());
            if (index % 12 == 0) indicator.setFraction(files.isEmpty() ? 0 : .4 * index / files.size());
            PsiFile f = manager.findFile(v);
            if (!(f instanceof PsiJavaFile)) continue;
            f.accept(new JavaRecursiveElementWalkingVisitor() {
                @Override public void visitClass(@NotNull PsiClass cls) {
                    String id = cls.getQualifiedName();
                    if (id != null && !id.isBlank()) {
                        ArrayList<GraphModel.MethodNode> methods = new ArrayList<>();
                        for (PsiMethod m : cls.getMethods()) {
                            methods.add(new GraphModel.MethodNode(methodId(m), displayMethod(m), m.getTextOffset()));
                        }
                        String pkg = ((PsiJavaFile) f).getPackageName();
                        classes.put(id, new GraphModel.ClassNode(id, cls.getName() == null ? id : cls.getName(),
                                pkg.isEmpty() ? "(default package)" : pkg, methods));
                    }
                    super.visitClass(cls);
                }
            });
        }
        // Pass 2: resolve actual method calls rather than guessing by matching method names.
        for (int index = 0; index < files.size(); index++) {
            ProgressManager.checkCanceled();
            VirtualFile v = files.get(index);
            if (!fileIndex.isInSourceContent(v)) continue;
            indicator.setText2("Вызовы: " + v.getName());
            if (index % 12 == 0) indicator.setFraction(files.isEmpty() ? .5 : .4 + .6 * index / files.size());
            PsiFile f = manager.findFile(v);
            if (!(f instanceof PsiJavaFile)) continue;
            f.accept(new JavaRecursiveElementWalkingVisitor() {
                @Override public void visitMethodCallExpression(@NotNull PsiMethodCallExpression call) {
                    ProgressManager.checkCanceled();
                    PsiMethod containingMethod = PsiTreeUtil.getParentOfType(call, PsiMethod.class);
                    PsiMethod target = call.resolveMethod();
                    if (target != null && containingMethod != null) {
                        PsiClass declaring = target.getContainingClass();
                        PsiClass calling = containingMethod.getContainingClass();
                        if (declaring != null && calling != null) {
                            String from = declaring.getQualifiedName();
                            String to = calling.getQualifiedName();
                            String mid = methodId(target);
                            if (from != null && to != null && classes.containsKey(from) && classes.containsKey(to)) {
                                // Ignore same-class calls: a class never needs a self-loop in the main view.
                                if (!from.equals(to)) {
                                    String key = from + "\u0000" + mid + "\u0000" + to;
                                    callCounts.merge(key, 1, Integer::sum);
                                    edgePrototypes.putIfAbsent(key, new GraphModel.CallEdge(from, mid, to, 0));
                                }
                            }
                        }
                    }
                    super.visitMethodCallExpression(call);
                }
            });
        }
        ArrayList<GraphModel.CallEdge> edges = new ArrayList<>();
        for (var ent : callCounts.entrySet()) {
            GraphModel.CallEdge original = edgePrototypes.get(ent.getKey());
            edges.add(new GraphModel.CallEdge(original.fromClass(), original.methodId(),
                    original.toClass(), ent.getValue()));
        }
        indicator.setFraction(1);
        return new GraphModel(classes.values(), edges);
    }

    public static String methodId(PsiMethod m) {
        PsiClass owner = m.getContainingClass();
        String cls = owner == null ? "?" : owner.getQualifiedName();
        StringBuilder b = new StringBuilder(cls).append('#').append(m.getName()).append('(');
        for (PsiParameter p : m.getParameterList().getParameters()) b.append(p.getType().getCanonicalText()).append(';');
        return b.append(')').toString();
    }

    private static String displayMethod(PsiMethod m) {
        StringBuilder b = new StringBuilder(m.getName()).append('(');
        PsiParameter[] params = m.getParameterList().getParameters();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) b.append(", ");
            b.append(params[i].getType().getPresentableText());
        }
        return b.append(')').toString();
    }
}
