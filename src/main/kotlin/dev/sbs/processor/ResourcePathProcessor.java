package dev.sbs.processor;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ex.InspectionProfileImpl;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.codeInspection.options.OptPane;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.profile.codeInspection.InspectionProjectProfileManager;
import com.intellij.psi.*;
import com.intellij.util.xmlb.annotations.OptionTag;
import dev.sbs.annotation.ResourcePath;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.uast.UBinaryExpression;
import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UExpression;
import org.jetbrains.uast.UFile;
import org.jetbrains.uast.ULiteralExpression;
import org.jetbrains.uast.UQualifiedReferenceExpression;
import org.jetbrains.uast.UReferenceExpression;
import org.jetbrains.uast.UastContextKt;
import org.jetbrains.uast.visitor.AbstractUastVisitor;

import java.util.List;
import java.util.Optional;

/**
 * Inspection processor that checks string literals annotated with {@code ResourcePath}
 * to verify if they correspond to existing resource paths on the classloader.
 * <p>
 * Supports an optional {@code base} parameter in the annotation to specify a base folder
 * under which the resource path is resolved.
 */
public class ResourcePathProcessor extends LocalInspectionTool {

    @OptionTag("HIGHLIGHT_TYPE_BASE")
    public ProblemHighlightType baseHighlightType = ProblemHighlightType.ERROR;

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

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {

            @Override
            public void visitFile(@NotNull PsiFile file) {
                UFile uFile = UastContextKt.toUElement(file, UFile.class);
                if (uFile == null) return;

                uFile.accept(new AbstractUastVisitor() {
                    @Override
                    public boolean visitCallExpression(@NotNull UCallExpression node) {
                        for (UExpression arg : node.getValueArguments()) {
                            if (arg instanceof ULiteralExpression
                                || arg instanceof UBinaryExpression
                                || arg instanceof UCallExpression
                                || arg instanceof UReferenceExpression) {
                                PsiAnnotation annotation = findResourcePathAnnotation(node);
                                if (annotation == null || !validateBaseFolder(holder, annotation)) continue;

                                for (String value : evaluateExpressions(arg)) {
                                    if (value == null || resourceExists(resolveFullPath(annotation, value), holder.getProject(), false)) continue;
                                    if (arg.getSourcePsi() == null) continue;
                                    holder.registerProblem(arg.getSourcePsi(), "Missing Resource File: " + resolveFullPath(annotation, value), getHighlightType(holder));
                                }
                            }
                        }

                        return true;
                    }
                });
            }

            @Override
            public void visitField(@NotNull PsiField field) {
                if (field.getInitializer() == null) return;

                getResourcePathAnnotation(field.getModifierList()).ifPresent(annotation -> {
                    if (!validateBaseFolder(holder, annotation)) return;
                    UExpression expr = UastContextKt.toUElement(field.getInitializer(), UExpression.class);
                    if (expr == null) return;

                    for (String value : evaluateExpressions(expr)) {
                        if (value != null && !resourceExists(resolveFullPath(annotation, value), holder.getProject(), false))
                            holder.registerProblem(field.getInitializer(), "Missing Resource File: " + resolveFullPath(annotation, value), getHighlightType(holder));
                    }
                });
            }

            @Override
            public void visitEnumConstant(@NotNull PsiEnumConstant enumConstant) {
                PsiMethod constructor = enumConstant.resolveConstructor();
                if (constructor == null || enumConstant.getArgumentList() == null) return;
                PsiExpression[] arguments = enumConstant.getArgumentList().getExpressions();
                PsiParameter[] parameters = constructor.getParameterList().getParameters();

                for (int i = 0; i < Math.min(arguments.length, parameters.length); i++) {
                    PsiParameter param = parameters[i];
                    PsiExpression arg = arguments[i];

                    getResourcePathAnnotation(param.getModifierList())
                        .filter(annotation -> validateBaseFolder(holder, annotation))
                        .ifPresent(annotation -> {
                            UExpression argU = UastContextKt.toUElement(arg, UExpression.class);
                            if (argU == null) return;

                            for (String value : evaluateExpressions(argU)) {
                                if (value != null && !resourceExists(resolveFullPath(annotation, value), holder.getProject(), false))
                                    holder.registerProblem(arg, "Missing Resource File: " + resolveFullPath(annotation, value), getHighlightType(holder));
                            }
                        });
                }
            }

        };
    }

    /**
     * Retrieves the {@code ResourcePath} annotation from the provided {@link PsiModifierList}, if present.
     *
     * @param modifierList the modifier list containing annotations; may be {@code null}
     * @return an {@code Optional} containing the {@code PsiAnnotation} if the {@code ResourcePath} annotation is present,
     *         or an empty {@code Optional} if not found or {@code modifierList} is {@code null}
     */
    private @NotNull Optional<PsiAnnotation> getResourcePathAnnotation(@Nullable PsiModifierList modifierList) {
        if (modifierList == null)
            return Optional.empty();

        for (PsiAnnotation annotation : modifierList.getAnnotations()) {
            String qName = annotation.getQualifiedName();

            if ("dev.sbs.annotations.ResourcePath".equals(qName) || (qName != null && qName.endsWith(".ResourcePath")))
                return Optional.of(annotation);
        }

        return Optional.empty();
    }

    /**
     * Evaluates the given {@link UExpression} and collects all possible string values
     * that can be resolved from it. This method supports literal expressions, method calls,
     * field references, and other scenarios where a string can be interpreted from the expression.
     *
     * @param expr the {@link UExpression} to evaluate; must not be null
     * @return a list of resolved string values derived from the expression; never null but may be empty
     */
    private @NotNull List<String> evaluateExpressions(@NotNull UExpression expr) {
        List<String> results = new java.util.ArrayList<>();
        String evaluated = evaluateStringExpression(expr);

        if (evaluated != null)
            results.add(evaluated);
        else if (expr instanceof UCallExpression callExpr) {
            PsiMethod resolvedMethod = callExpr.resolve();

            if (resolvedMethod != null && resolvedMethod.getBody() != null) {
                for (PsiStatement statement : resolvedMethod.getBody().getStatements()) {
                    if (statement instanceof PsiReturnStatement returnStmt) {
                        if (returnStmt.getReturnValue() == null) continue;
                        UExpression uReturn = UastContextKt.toUElement(returnStmt.getReturnValue(), UExpression.class);
                        if (uReturn == null) continue;
                        String returnValue = evaluateStringExpression(uReturn);
                        if (returnValue != null) results.add(returnValue);
                    }
                }
            }
        } else if (expr instanceof UReferenceExpression ref) {
            if (ref.resolve() instanceof PsiField field && field.hasModifierProperty(PsiModifier.FINAL) && field.getInitializer() != null) {
                UExpression fieldExpr = UastContextKt.toUElement(field.getInitializer(), UExpression.class);

                if (fieldExpr != null) {
                    String fieldValue = evaluateStringExpression(fieldExpr);
                    if (fieldValue != null) results.add(fieldValue);
                }
            }
        }

        return results;
    }

    /**
     * Evaluates a given {@link UExpression} to determine its string value.
     * Supports literal expressions, binary concatenation of string expressions,
     * reference expressions that resolve to variables, and qualified reference expressions
     * for resolving enum field access.
     *
     * @param expr the {@link UExpression} to evaluate; must not be null
     * @return the resolved string value if evaluation is successful, or {@code null} if the expression
     *         cannot be resolved to a string
     */
    private @Nullable String evaluateStringExpression(@NotNull UExpression expr) {
        if (expr instanceof ULiteralExpression literal && literal.getValue() instanceof String strVal)
            return strVal;

        if (expr instanceof UBinaryExpression binExpr && "+".equals(binExpr.getOperator().getText())) {
            String left = evaluateStringExpression(binExpr.getLeftOperand());
            String right = evaluateStringExpression(binExpr.getRightOperand());
            if (left != null && right != null) return left + right;
        }

        if (expr instanceof UReferenceExpression ref) {
            PsiElement resolved = ref.resolve();

            if (resolved instanceof PsiVariable variable && variable.getInitializer() != null) {
                UExpression initU = UastContextKt.toUElement(variable.getInitializer(), UExpression.class);
                if (initU != null) return evaluateStringExpression(initU);
            }
        }

        if (expr instanceof UQualifiedReferenceExpression qualified)
            return resolveEnumFieldAccess(qualified);

        return null;
    }

    /**
     * Resolves the value of an enum field from a qualified reference expression, if possible.
     * This method identifies whether the given reference expression targets an enum constant
     * and seeks to resolve the desired field's value within the constructor of that enum constant.
     *
     * @param qualifiedExpr the qualified reference expression representing the enum field access; must not be null
     * @return the resolved string value for the enum field if successfully determined, or null if it cannot be resolved
     */
    private @Nullable String resolveEnumFieldAccess(@NotNull UQualifiedReferenceExpression qualifiedExpr) {
        UExpression receiver = qualifiedExpr.getReceiver();
        String selectorName = qualifiedExpr.getResolvedName();

        if (receiver instanceof UQualifiedReferenceExpression receiverQualified) {
            PsiElement baseResolved = receiverQualified.resolve();
            if (baseResolved instanceof PsiEnumConstant enumConst && selectorName != null) {
                PsiClass enumClass = enumConst.getContainingClass();
                if (enumClass == null) return null;

                PsiField targetField = enumClass.findFieldByName(selectorName, false);
                if (targetField == null) return null;

                PsiMethod constructor = enumConst.resolveConstructor();
                if (constructor == null || enumConst.getArgumentList() == null) return null;

                PsiExpression[] args = enumConst.getArgumentList().getExpressions();
                PsiCodeBlock body = constructor.getBody();

                for (int i = 0; i < args.length; i++) {
                    PsiParameter param = constructor.getParameterList().getParameters()[i];
                    if (body == null) continue;

                    for (PsiStatement statement : body.getStatements()) {
                        if (statement instanceof PsiExpressionStatement exprStmt &&
                            exprStmt.getExpression() instanceof PsiAssignmentExpression assignExpr) {
                            PsiExpression lhs = assignExpr.getLExpression();
                            PsiExpression rhs = assignExpr.getRExpression();

                            if (lhs instanceof PsiReferenceExpression leftRef &&
                                rightSideMatchesParam(rhs, param) &&
                                leftRef.getReferenceName() != null &&
                                leftRef.getReferenceName().equals(targetField.getName())) {

                                UExpression argUExpr = UastContextKt.toUElement(args[i], UExpression.class);
                                if (argUExpr != null) return evaluateStringExpression(argUExpr);
                            }
                        }
                    }
                }
            }
        }

        return null;
    }

    private boolean rightSideMatchesParam(@Nullable PsiExpression rhs, @NotNull PsiParameter param) {
        return rhs instanceof PsiReferenceExpression ref && ref.resolve() == param;
    }

    private @Nullable PsiAnnotation findResourcePathAnnotation(@NotNull UCallExpression call) {
        PsiMethod resolved = call.resolve();
        if (resolved == null) return null;

        for (PsiParameter param : resolved.getParameterList().getParameters()) {
            PsiAnnotation anno = param.getAnnotation("dev.sbs.annotations.ResourcePath");
            if (anno != null) return anno;
        }

        return null;
    }

    /**
     * Retrieves the 'base' folder from the {@code ResourcePath} annotation if present.
     * If not present, returns an empty string (root).
     *
     * @param annotation the ResourcePath annotation to check
     * @return the base folder path or empty string if none specified
     */
    private @NotNull String getBaseFolder(@NotNull PsiAnnotation annotation) {
        if (annotation.findAttributeValue("base") instanceof PsiLiteralExpression literal && literal.getValue() instanceof String strValue)
            return strValue;

        return "";
    }

    /**
     * Validates that the base folder specified in the given {@code ResourcePath} annotation exists.
     * If the base folder does not exist, registers a problem with the provided {@link ProblemsHolder}.
     *
     * @param holder the {@link ProblemsHolder} used to report validation issues
     * @param annotation the {@code ResourcePath} annotation containing the base folder information
     * @return {@code true} if the base folder exists or validation passes; {@code false} otherwise
     */
    private boolean validateBaseFolder(@NotNull ProblemsHolder holder, @NotNull PsiAnnotation annotation) {
        String base = this.getBaseFolder(annotation);

        if (!this.resourceExists(base, holder.getProject(), true)) {
            PsiNameValuePair[] attributes = annotation.getParameterList().getAttributes();

            for (PsiNameValuePair pair : attributes) {
                if ("base".equals(pair.getName()) && pair.getValue() != null) {
                    holder.registerProblem( pair.getValue(), "Invalid Base Directory: " + base, this.baseHighlightType);
                    return false;
                }
            }
        }

        return true;
    }

    private String resolveFullPath(@NotNull PsiAnnotation annotation, @NotNull String value) {
        String base = getBaseFolder(annotation);
        return base.isEmpty() ? value : base + "/" + value;
    }

    /**
     * Checks if the resource exists in the given project's resource directory.
     *
     * @param path the resource path
     * @param project the project to check
     * @return true if resource exists or path is null/empty, false otherwise
     */
    private boolean resourceExists(String path, @NotNull Project project, boolean isDirectory) {
        if (path == null || path.trim().isEmpty()) return true;

        String normalizedPath = path.replace('\\', '/');
        String basePath = project.getBasePath();
        if (basePath == null) return false;

        VirtualFile baseDir = VirtualFileManager.getInstance().findFileByUrl("file://" + basePath);
        if (baseDir == null) return false;

        // Try relative to source/resource roots
        for (VirtualFile root : ProjectRootManager.getInstance(project).getContentSourceRoots()) {
            VirtualFile candidate = root.findFileByRelativePath(normalizedPath);
            if (candidate != null) return isDirectory == candidate.isDirectory();
        }

        return false;
    }

    /**
     * Determines the appropriate highlight type to be applied for a detected problem.
     * The method retrieves the inspection profile and resolves the highlight type
     * based on the severity of the issue in the given context.
     *
     * @param holder the {@link ProblemsHolder} containing the context of the inspection;
     *               must not be null
     * @return the {@link ProblemHighlightType} corresponding to the severity of the problem;
     *         never null
     */
    private @NotNull ProblemHighlightType getHighlightType(@NotNull ProblemsHolder holder) {
        InspectionProfileImpl profile = InspectionProjectProfileManager.getInstance(holder.getProject()).getCurrentProfile();
        InspectionToolWrapper<?, ?> inspectionTool = profile.getInspectionTool(this.getShortName(), holder.getFile());
        if (inspectionTool == null || inspectionTool.getDisplayKey() == null) return ProblemHighlightType.ERROR;
        HighlightSeverity severity = profile.getErrorLevel(inspectionTool.getDisplayKey(), holder.getFile()).getSeverity();
        return mapSeverityToHighlightType(severity);
    }

    private @NotNull ProblemHighlightType mapSeverityToHighlightType(@NotNull HighlightSeverity severity) {
        return switch (severity.getName()) {
            case "ERROR" -> ProblemHighlightType.ERROR;
            case "WARNING" -> ProblemHighlightType.WARNING;
            case "GENERIC_SERVER_ERROR_OR_WARNING" -> ProblemHighlightType.GENERIC_ERROR_OR_WARNING;
            case "INFORMATION", "INFO" -> ProblemHighlightType.INFORMATION;
            default -> ProblemHighlightType.WEAK_WARNING;
        };
    }

}
