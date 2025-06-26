package dev.sbs.inspection;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.options.OptPane;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.util.xmlb.annotations.OptionTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UastContextKt;

/**
 * Inspection processor that checks string literals annotated with {@code ResourcePath}
 * to verify if they correspond to existing resource paths on the classloader.
 * <p>
 * Supports an optional {@code base} parameter in the annotation to specify a base folder
 * under which the resource path is resolved.
 */
class ResourcePathInspection extends LocalInspectionTool {

    @OptionTag("HIGHLIGHT_TYPE_BASE")
    public @NotNull ProblemHighlightType baseHighlightType = ProblemHighlightType.ERROR;

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        ResourcePathVisitor resourcePathVisitor = new ResourcePathVisitor(this, holder, this.baseHighlightType);

        return new JavaElementVisitor() {

            @Override
            public void visitEnumConstant(@NotNull PsiEnumConstant enumConstant) {
                resourcePathVisitor.inspectEnumArguments(enumConstant);
            }

            @Override
            public void visitField(@NotNull PsiField field) {
                resourcePathVisitor.inspectField(field);
            }

            @Override
            public void visitLiteralExpression(@NotNull PsiLiteralExpression expression) {
                resourcePathVisitor.inspectLiteral(expression);
            }

            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                UCallExpression uCall = UastContextKt.toUElement(expression, UCallExpression.class);

                if (uCall != null)
                    resourcePathVisitor.inspectMethod(uCall);
            }

        };
    }

    @Override
    public @NotNull OptPane getOptionsPane() {
        return OptPane.pane(
            OptPane.group(
                "Highlight settings",
                OptPane.dropdown(
                    "baseHighlightType",
                    "Highlight for invalid base directory",
                    OptPane.option(ProblemHighlightType.ERROR, "Error"),
                    OptPane.option(ProblemHighlightType.WARNING, "Warning"),
                    OptPane.option(ProblemHighlightType.WEAK_WARNING, "Weak Warning"),
                    OptPane.option(ProblemHighlightType.GENERIC_ERROR_OR_WARNING, "Server Problem"),
                    OptPane.option(ProblemHighlightType.INFORMATION, "Information")
                )
            )
        );
    }

}
