package dev.sbs.annotation.inspection;

import com.esotericsoftware.kryo.kryo5.util.Null;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.ex.InspectionProfileImpl;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.profile.codeInspection.InspectionProjectProfileManager;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UExpression;
import org.jetbrains.uast.UField;
import org.jetbrains.uast.UastContextKt;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

class ResourcePathVisitor {

    private final @NotNull Set<PsiAnnotation> visitedAnnotations = new HashSet<>();
    private final @NotNull Set<PsiElement> inspectedExpressions = new HashSet<>();
    private final @NotNull String annotationPath = "dev.sbs.annotation.ResourcePath";
    private final @NotNull LocalInspectionTool inspectionTool;
    private final @NotNull ProblemsHolder holder;
    private final @NotNull ProblemHighlightType baseHighlightType;

    public ResourcePathVisitor(@NotNull LocalInspectionTool inspectionTool, @NotNull ProblemsHolder holder, @NotNull ProblemHighlightType baseHighlightType) {
        this.inspectionTool = inspectionTool;
        this.holder = holder;
        this.baseHighlightType = baseHighlightType;
    }

    public void inspectMethod(@NotNull UCallExpression callExpr) {
        PsiMethod method = callExpr.resolve();
        if (method == null) return;
        PsiParameter[] parameters = method.getParameterList().getParameters();
        List<UExpression> arguments = callExpr.getValueArguments();

        this.inspectMethodReturnValue(callExpr, method.getAnnotation(this.annotationPath));

        for (int i = 0; i < Math.min(arguments.size(), parameters.length); i++)
            this.inspectArgument(arguments.get(i), parameters[i].getAnnotation(this.annotationPath));
    }

    public void inspectField(@NotNull PsiField field) {
        UField uField = UastContextKt.toUElement(field, UField.class);
        if (uField == null) return;
        UExpression expression = uField.getUastInitializer();
        if (expression == null) return;

        if (expression instanceof UCallExpression uCall)
            this.inspectMethodReturnValue(uCall, field.getAnnotation(this.annotationPath));
        else if (expression instanceof PsiEnumConstant enumConst)
            this.inspectEnumArguments(enumConst);
        else
            this.inspectArgument(expression, field.getAnnotation(this.annotationPath));
    }

    public void inspectLiteral(@NotNull PsiLiteralExpression literalExpr) {
        PsiElement current = literalExpr;

        while (current != null) {
            if (current instanceof PsiMethod method) {
                this.inspectMethodCallRecursive(method, new HashSet<>());
                break;
            }

            current = current.getParent();
        }
    }

    private void inspectMethodReturnValue(@NotNull UCallExpression expression, @Null PsiAnnotation annotation) {
        PsiMethod method = expression.resolve();
        if (method == null || method.getBody() == null) return;

        for (PsiStatement stmt : method.getBody().getStatements()) {
            if (stmt instanceof PsiReturnStatement returnStmt) {
                PsiExpression returnExpr = returnStmt.getReturnValue();
                if (returnExpr == null) continue;
                UExpression uReturnExpr = UastContextKt.toUElement(returnExpr, UExpression.class);

                if (uReturnExpr != null)
                    this.inspectArgument(expression, annotation);
            }
        }
    }

    private void inspectMethodCallRecursive(@NotNull PsiMethod method, @NotNull Set<PsiMethod> visitedMethods) {
        if (!visitedMethods.add(method)) return; // Avoid infinite recursion
        Project project = method.getProject();
        SearchScope scope = GlobalSearchScope.projectScope(project);

        // Find all usages of this method
        for (PsiReference reference : ReferencesSearch.search(method, scope)) {
            // Go up to find field that uses the method
            PsiElement current = reference.getElement();

            while (current != null) {
                if (current instanceof PsiField field && field.getAnnotation(this.annotationPath) != null) {
                    this.inspectField(field);
                    break;
                } else if (current instanceof PsiMethod parentMethod) {
                    this.inspectMethodCallRecursive(parentMethod, visitedMethods);
                    break;
                }

                if (current instanceof PsiClass) break; // Avoid traversing project
                current = current.getParent();
            }
        }
    }

    public void inspectEnumArguments(@NotNull PsiEnumConstant enumConstant) {
        PsiMethod constructor = enumConstant.resolveConstructor();
        if (constructor == null) return;
        PsiExpressionList args = enumConstant.getArgumentList();
        if (args == null) return;
        PsiParameter[] parameters = constructor.getParameterList().getParameters();
        PsiExpression[] arguments = args.getExpressions();

        for (int i = 0; i < Math.min(arguments.length, parameters.length); i++) {
            UExpression uArg = UastContextKt.toUElement(arguments[i], UExpression.class);
            if (uArg == null) continue;
            this.inspectArgument(uArg, parameters[i].getAnnotation(this.annotationPath));
        }
    }

    private void inspectArgument(
        @NotNull UExpression expression,
        @Nullable PsiAnnotation annotation
    ) {
        if (annotation == null) return;

        if (this.visitedAnnotations.add(annotation)) {
            if (!this.validateBaseFolder(annotation))
                return;
        }

        PsiElement source = expression.getSourcePsi();
        if (source == null || !this.inspectedExpressions.add(source)) return;
        Set<String> resolvedValues = StringExpressionEvaluator.evaluate(expression);

        for (String value : resolvedValues) {
            if (value == null || value.isEmpty()) continue;
            String resourcePath = this.resolveFullPath(annotation, value);
            if (this.resourceExists(resourcePath, this.holder.getProject(), false)) continue;
            this.holder.registerProblem(source, "Missing Resource File: " + resourcePath, this.getHighlightType());
        }
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
     * @param annotation the {@code ResourcePath} annotation containing the base folder information
     * @return {@code true} if the base folder exists or validation passes; {@code false} otherwise
     */
    private boolean validateBaseFolder(@NotNull PsiAnnotation annotation) {
        String base = getBaseFolder(annotation);

        if (!resourceExists(base, this.holder.getProject(), true)) {
            PsiNameValuePair[] attributes = annotation.getParameterList().getAttributes();

            for (PsiNameValuePair pair : attributes) {
                if ("base".equals(pair.getName()) && pair.getValue() != null) {
                    this.holder.registerProblem(pair.getValue(), "Invalid Base Directory: " + base, this.baseHighlightType);
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
     * @return the {@link ProblemHighlightType} corresponding to the severity of the problem;
     *         never null
     */
    private @NotNull ProblemHighlightType getHighlightType() {
        InspectionProfileImpl profile = InspectionProjectProfileManager.getInstance(this.holder.getProject()).getCurrentProfile();
        InspectionToolWrapper<?, ?> inspectionTool = profile.getInspectionTool(this.inspectionTool.getShortName(), this.holder.getFile());
        if (inspectionTool == null || inspectionTool.getDisplayKey() == null) return ProblemHighlightType.ERROR;
        HighlightSeverity severity = profile.getErrorLevel(inspectionTool.getDisplayKey(), this.holder.getFile()).getSeverity();
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
