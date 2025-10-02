package dev.sbs.inspection;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.DumbService;
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
                if (DumbService.isDumb(project)) return;

                // Early exit: Only process if we're in a file that contains ResourcePath annotations
                PsiFile containingFile = parent.getContainingFile();
                if (containingFile == null || !this.fileContainsResourcePathUsage(containingFile))
                    return;

                // Check if the parent itself or any direct ancestor is annotated with @ResourcePath
                // This prevents processing unrelated string literals
                if (!isRelevantToResourcePath(parent))
                    return;

                // Find all string literals in the changed subtree
                //Collection<PsiLiteralExpression> literals = PsiTreeUtil.findChildrenOfType(parent, PsiLiteralExpression.class);
                Collection<PsiLiteralExpression> literals = this.findImmediateLiterals(parent);
                for (PsiLiteralExpression literal : literals)
                    collectAffectedFile(literal);

                restartAffectedFiles();
            }

            /**
             * Checks if the given element or its ancestors are relevant to ResourcePath processing
             */
            private boolean isRelevantToResourcePath(@NotNull PsiElement element) {
                PsiElement current = element;

                // Traverse up to method or field level
                while (current != null && !(current instanceof PsiFile)) {
                    if (current instanceof PsiMethod method)
                        return hasResourcePathAnnotation(method.getModifierList());

                    if (current instanceof PsiField field)
                        return hasResourcePathAnnotation(field.getModifierList());

                    if (current instanceof PsiClass)
                        break; // Stop at class level to avoid traversing too far

                    current = current.getParent();
                }

                return false;
            }

            /**
             * Finds literal expressions only in the immediate children, not deeply nested
             * This reduces the scope of processing significantly
             */
            private Collection<PsiLiteralExpression> findImmediateLiterals(@NotNull PsiElement parent) {
                Set<PsiLiteralExpression> literals = new HashSet<>();

                // Only look at direct children and their immediate children (max depth 2)
                parent.accept(new JavaRecursiveElementWalkingVisitor() {
                    private int depth = 0;

                    @Override
                    public void visitElement(@NotNull PsiElement element) {
                        if (depth > 2) return; // Limit traversal depth

                        if (element instanceof PsiLiteralExpression literal) {
                            literals.add(literal);
                        }

                        depth++;
                        super.visitElement(element);
                        depth--;
                    }
                });

                return literals;
            }

            private boolean hasResourcePathAnnotation(@Nullable PsiModifierList modifierList) {
                if (modifierList == null) return false;

                return Arrays.stream(modifierList.getAnnotations())
                    .anyMatch(anno -> annotationPath.equals(anno.getQualifiedName()));
            }

            private void collectAffectedFile(@Nullable PsiElement element) {
                if (!(element instanceof PsiLiteralExpression literal)) return;
                if (!(literal.getValue() instanceof String)) return;
                if (DumbService.isDumb(project)) return;

                try {
                    PsiFile file = literal.getContainingFile();
                    if (file == null) return;

                    // Climb up to enclosing method or field
                    PsiMethod enclosingMethod = PsiTreeUtil.getParentOfType(literal, PsiMethod.class);
                    PsiField enclosingField = PsiTreeUtil.getParentOfType(literal, PsiField.class);

                    if (enclosingField != null && hasResourcePathAnnotation(enclosingField.getModifierList())) {
                        filesToRestart.add(file);
                        return;
                    }

                    if (enclosingMethod != null && hasResourcePathAnnotation(enclosingMethod.getModifierList())) {
                        filesToRestart.add(file);
                        return;
                    }

                    // Fallback: restart if we're inside any file containing ResourcePath annotations
                    // and the method may be called from such a place.
                    if (this.fileContainsResourcePathUsage(file)) {
                        filesToRestart.add(file);
                    }
                } catch (PsiInvalidElementAccessException ignored) {}
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
