package dev.sbs.inspection;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

@Service(Service.Level.PROJECT)
final class ResourcePathChangeService {

    private final @NotNull String annotationPath = "dev.sbs.annotation.ResourcePath";
    private final @NotNull Project project;

    public ResourcePathChangeService(@NotNull Project project) {
        this.project = project;
        PsiManager.getInstance(project).addPsiTreeChangeListener(this.getListenerAdapter(), project.getMessageBus().connect());
    }

    private @NotNull PsiTreeChangeAdapter getListenerAdapter() {
        return new PsiTreeChangeAdapter() {

            private final Set<PsiFile> filesToRestart = new HashSet<>();

            @Override
            public void childReplaced(@NotNull PsiTreeChangeEvent event) {
                collectAffectedFile(event.getNewChild());
                collectAffectedFile(event.getOldChild());
                restartAffectedFiles();
            }

            @Override
            public void childAdded(@NotNull PsiTreeChangeEvent event) {
                collectAffectedFile(event.getChild());
                restartAffectedFiles();
            }

            @Override
            public void childRemoved(@NotNull PsiTreeChangeEvent event) {
                collectAffectedFile(event.getOldChild());
                restartAffectedFiles();
            }

            @Override
            public void childrenChanged(@NotNull PsiTreeChangeEvent event) {
                PsiElement parent = event.getParent();
                if (parent == null || !parent.isValid()) return;

                // Find all string literals in the changed subtree
                Collection<PsiLiteralExpression> literals = PsiTreeUtil.findChildrenOfType(parent, PsiLiteralExpression.class);
                for (PsiLiteralExpression literal : literals)
                    collectAffectedFile(literal);

                restartAffectedFiles();
            }

            private boolean hasResourcePathAnnotation(@Nullable PsiModifierList modifierList) {
                if (modifierList == null) return false;
                return Arrays.stream(modifierList.getAnnotations())
                    .anyMatch(anno -> annotationPath.equals(anno.getQualifiedName()));
            }

            private void collectAffectedFile(@Nullable PsiElement element) {
                if (!(element instanceof PsiLiteralExpression literal)) return;
                if (!(literal.getValue() instanceof String)) return;

                PsiFile file = literal.getContainingFile();
                if (file == null) return;

                // Climb up to enclosing method or field
                PsiMethod enclosingMethod = PsiTreeUtil.getParentOfType(literal, PsiMethod.class);
                PsiField enclosingField = PsiTreeUtil.getParentOfType(literal, PsiField.class);

                if (enclosingField != null && hasResourcePathAnnotation(enclosingField.getModifierList())) {
                    System.out.println("Adding file for: " + enclosingField);
                    filesToRestart.add(file);
                    return;
                }

                if (enclosingMethod != null && hasResourcePathAnnotation(enclosingMethod.getModifierList())) {
                    System.out.println("Adding file for: " + enclosingMethod);
                    filesToRestart.add(file);
                    return;
                }

                // Fallback: restart if we're inside any file containing ResourcePath annotations
                // and the method may be called from such a place.
                if (fileContainsResourcePathUsage(file)) {
                    filesToRestart.add(file);
                }
            }

            private void restartAffectedFiles() {
                for (PsiFile file : this.filesToRestart)
                    DaemonCodeAnalyzer.getInstance(project).restart(file);

                this.filesToRestart.clear();
            }

            private boolean fileContainsResourcePathUsage(@NotNull PsiFile file) {
                return PsiTreeUtil.findChildrenOfType(file, PsiAnnotation.class)
                    .stream()
                    .anyMatch(anno -> annotationPath.equals(anno.getQualifiedName()));
            }

        };
    }

}
