package dev.readablegraph.idea;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProcessCanceledException;
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

import java.nio.file.Path;
import java.util.*;

/** Two-pass PSI index, restricted to the explicitly chosen project source folder.
 * All classes and all resolvable cross-class call relationships are kept.
 * Read locks are acquired per file, rather than holding a read action for an entire
 * potentially enormous project. This allows IDEA to keep responding to edits.
 */
public final class JavaCallScanner {
    private JavaCallScanner() {}

    public static GraphModel scan(Project project, Path selectedFolder, ProgressIndicator indicator) {
        Path root = selectedFolder.toAbsolutePath().normalize();
        List<VirtualFile> files = ReadAction.compute(() -> {
            ProjectFileIndex index = ProjectFileIndex.getInstance(project);
            List<VirtualFile> result = new ArrayList<>();
            for (VirtualFile file : FileTypeIndex.getFiles(JavaFileType.INSTANCE,
                    GlobalSearchScope.projectScope(project))) {
                indicator.checkCanceled();
                if (!index.isInSourceContent(file) || !file.isInLocalFileSystem()) continue;
                try {
                    if (Path.of(file.getPath()).toAbsolutePath().normalize().startsWith(root)) result.add(file);
                } catch (RuntimeException ignored) {
                    // Ignore virtual paths that are not valid local filesystem paths.
                }
            }
            return result;
        });
        files.sort(Comparator.comparing(VirtualFile::getPath));
        LinkedHashMap<String, GraphModel.ClassNode> classes = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        LinkedHashMap<String, GraphModel.CallEdge> prototypes = new LinkedHashMap<>();
        int failures = 0;

        // Pass 1: classes and method signatures.
        for (int i = 0; i < files.size(); i++) {
            indicator.checkCanceled();
            int at = i;
            indicator.setText("Анализ классов: " + (i + 1) + "/" + files.size());
            indicator.setText2(files.get(i).getPresentableName());
            indicator.setFraction(files.isEmpty() ? .40 : .40 * (i + 1) / files.size());
            try {
                ReadAction.run(() -> {
                    PsiFile file = PsiManager.getInstance(project).findFile(files.get(at));
                    if (!(file instanceof PsiJavaFile javaFile)) return;
                    file.accept(new JavaRecursiveElementWalkingVisitor() {
                        @Override public void visitClass(@NotNull PsiClass cls) {
                            indicator.checkCanceled();
                            String id = cls.getQualifiedName();
                            if (id != null && !id.isBlank()) {
                                ArrayList<GraphModel.MethodNode> methods = new ArrayList<>();
                                for (PsiMethod m : cls.getMethods()) {
                                    methods.add(new GraphModel.MethodNode(methodId(m), displayMethod(m), m.getTextOffset()));
                                }
                                String pkg = javaFile.getPackageName();
                                String simple = cls.getName();
                                classes.put(id, new GraphModel.ClassNode(id, simple == null ? id : simple,
                                        pkg == null || pkg.isEmpty() ? "(default package)" : pkg, methods));
                            }
                            super.visitClass(cls);
                        }
                    });
                });
            } catch (ProcessCanceledException canceled) {
                throw canceled;
            } catch (RuntimeException malformedFile) {
                failures++;
                // One malformed Java file must not abort an otherwise large graph.
            }
        }

        // Pass 2: calls, including constructor invocations.
        for (int i = 0; i < files.size(); i++) {
            indicator.checkCanceled();
            int at = i;
            indicator.setText("Поиск вызовов: " + (i + 1) + "/" + files.size());
            indicator.setText2(files.get(i).getPresentableName());
            indicator.setFraction(files.isEmpty() ? 1 : .40 + .60 * (i + 1) / files.size());
            try {
                ReadAction.run(() -> {
                    PsiFile file = PsiManager.getInstance(project).findFile(files.get(at));
                    if (!(file instanceof PsiJavaFile)) return;
                    file.accept(new JavaRecursiveElementWalkingVisitor() {
                        private void register(PsiElement call, PsiMethod target) {
                            indicator.checkCanceled();
                            if (target == null) return;
                            PsiMethod enclosing = PsiTreeUtil.getParentOfType(call, PsiMethod.class);
                            if (enclosing == null) return;
                            PsiClass declaredIn = target.getContainingClass();
                            PsiClass calledFrom = enclosing.getContainingClass();
                            if (declaredIn == null || calledFrom == null) return;
                            String from = declaredIn.getQualifiedName(), to = calledFrom.getQualifiedName();
                            if (from == null || to == null || from.equals(to)) return;
                            if (!classes.containsKey(from) || !classes.containsKey(to)) return;
                            String mid = methodId(target);
                            String key = from + "\u0000" + mid + "\u0000" + to;
                            counts.merge(key, 1, Integer::sum);
                            prototypes.putIfAbsent(key, new GraphModel.CallEdge(from, mid, to, 0));
                        }
                        @Override public void visitMethodCallExpression(@NotNull PsiMethodCallExpression call) {
                            register(call, call.resolveMethod());
                            super.visitMethodCallExpression(call);
                        }
                        @Override public void visitNewExpression(@NotNull PsiNewExpression call) {
                            register(call, call.resolveConstructor());
                            super.visitNewExpression(call);
                        }
                    });
                });
            } catch (ProcessCanceledException canceled) {
                throw canceled;
            } catch (RuntimeException malformedFile) {
                failures++;
            }
        }
        ArrayList<GraphModel.CallEdge> edges = new ArrayList<>(counts.size());
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            GraphModel.CallEdge edge = prototypes.get(entry.getKey());
            edges.add(new GraphModel.CallEdge(edge.fromClass(), edge.methodId(), edge.toClass(), entry.getValue()));
        }
        indicator.setFraction(1);
        if (failures > 0) indicator.setText2("Некоторые файлы пропущены из-за ошибок: " + failures);
        return new GraphModel(classes.values(), edges);
    }

    public static String methodId(PsiMethod m) {
        PsiClass owner = m.getContainingClass();
        String cls = owner == null ? "?" : owner.getQualifiedName();
        if (cls == null) cls = "?";
        String name = m.getName();
        StringBuilder b = new StringBuilder(cls).append('#').append(name == null ? "?" : name).append('(');
        for (PsiParameter p : m.getParameterList().getParameters()) {
            String type = p.getType().getCanonicalText();
            b.append(type == null ? "?" : type).append(';');
        }
        return b.append(')').toString();
    }

    private static String displayMethod(PsiMethod m) {
        String name = m.getName();
        StringBuilder b = new StringBuilder(name == null ? "?" : name).append('(');
        PsiParameter[] params = m.getParameterList().getParameters();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) b.append(", ");
            String type = params[i].getType().getPresentableText();
            b.append(type == null ? "?" : type);
        }
        return b.append(')').toString();
    }
}
