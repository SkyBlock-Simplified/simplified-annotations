package dev.sbs.util;

import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.uast.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class StringExpressionEvaluator {

    public static @NotNull Set<String> evaluate(@NotNull UExpression expression) {
        return evaluate(expression, new HashSet<>(), new HashMap<>());//.values;
    }

    private static @NotNull Set<String> evaluate(
        @NotNull UExpression expression,
        @NotNull Set<PsiMethod> visitedMethods,
        @NotNull Map<String, Set<String>> intermediateVars
    ) {
        Set<String> result = new HashSet<>();
        expression = UastContextKt.toUElement(expression.getSourcePsi(), UExpression.class); // Prevent Stale Reference

        if (expression instanceof ULiteralExpression literal && literal.getValue() instanceof String value) { // Literals
            result.add(value);
        } else if (expression instanceof UPolyadicExpression polyadic) { // Concatenation
            Set<String> combinedResults = new HashSet<>();

            combineOperandsWithDeps(
                polyadic.getOperands(),
                0,
                "",
                combinedResults,
                visitedMethods,
                intermediateVars
            );

            result.addAll(combinedResults);
        } else if (expression instanceof USimpleNameReferenceExpression ref) { // Fields & Local Variables
            String name = ref.getIdentifier();

            if (intermediateVars.containsKey(name)) {
                Set<String> subResult = intermediateVars.get(name);
                result.addAll(subResult);
            } else {
                PsiElement resolved = ref.resolve();
                UExpression initExpr = null;

                if (resolved instanceof PsiField field && field.hasModifierProperty(PsiModifier.FINAL) && field.getInitializer() != null)
                    initExpr = UastContextKt.toUElement(field.getInitializer(), UExpression.class);
                else if (resolved instanceof PsiLocalVariable local && local.getInitializer() != null)
                    initExpr = UastContextKt.toUElement(local.getInitializer(), UExpression.class);

                if (initExpr != null)
                    result.addAll(evaluate(initExpr, visitedMethods, intermediateVars));
            }
        } else if (expression instanceof UCallExpression callExpr) { // Method Calls
            PsiMethod method = callExpr.resolve();

            if (method != null && !visitedMethods.contains(method)) {
                visitedMethods.add(method);
                PsiCodeBlock body = method.getBody();

                if (body != null) {
                    // Evaluate arguments of the call
                    List<UExpression> args = callExpr.getValueArguments();
                    PsiParameter[] params = method.getParameterList().getParameters();

                    // Map parameters to evaluated argument values
                    Map<String, Set<String>> paramBindings = new HashMap<>();
                    for (int i = 0; i < Math.min(args.size(), params.length); i++) {
                        Set<String> argEval = evaluate(args.get(i), visitedMethods, intermediateVars);
                        paramBindings.put(params[i].getName(), argEval);
                    }

                    Map<String, Set<String>> localVars = new HashMap<>(paramBindings);

                    // Evaluate method body recursively
                    for (PsiStatement statement : body.getStatements()) {
                        if (statement instanceof PsiDeclarationStatement declStmt) {
                            for (PsiElement element : declStmt.getDeclaredElements()) {
                                if (element instanceof PsiLocalVariable local && local.getInitializer() != null) {
                                    UExpression initExpr = UastContextKt.toUElement(local.getInitializer(), UExpression.class);

                                    if (initExpr != null)
                                        localVars.put(local.getName(), evaluate(initExpr, visitedMethods, localVars));
                                }
                            }
                        }
                    }

                    // Return Statements
                    for (PsiReturnStatement returnStmt : collectReturnStatements(body)) {
                        PsiExpression returnValue = returnStmt.getReturnValue();

                        if (returnValue != null) {
                            UExpression returnExpr = UastContextKt.toUElement(returnValue, UExpression.class);

                            if (returnExpr != null)
                                result.addAll(evaluate(returnExpr, visitedMethods, localVars));
                        }
                    }
                }

                visitedMethods.remove(method);
            }
        } else if (expression instanceof UQualifiedReferenceExpression qualified) { // Enum Fields
            result.addAll(resolveEnumFieldAccess(qualified, visitedMethods, intermediateVars));
        } else if (expression instanceof UDeclarationsExpression declarations) { // UAST Local Variables
            for (UDeclaration decl : declarations.getDeclarations()) {
                if (decl instanceof UVariable local) {
                    UExpression initExpr = local.getUastInitializer();
                    if (initExpr == null) continue;
                    result.addAll(evaluate(initExpr, visitedMethods, intermediateVars));
                }
            }
        }

        return result;
    }

    @SuppressWarnings("all")
    private static Set<PsiReturnStatement> collectReturnStatements(@NotNull PsiElement element) {
        Set<PsiReturnStatement> results = new HashSet<>();

        element.accept(new JavaRecursiveElementWalkingVisitor() {
            @Override
            public void visitReturnStatement(@NotNull PsiReturnStatement statement) {
                results.add(statement);
            }
        });

        return results;
    }

    private static void combineOperandsWithDeps(
        List<UExpression> operands,
        int index,
        String current,
        Set<String> resultValues,
        Set<PsiMethod> visitedMethods,
        Map<String, Set<String>> intermediateVars
    ) {
        if (index >= operands.size()) {
            resultValues.add(current);
            return;
        }

        UExpression operand = operands.get(index);
        Set<String> eval = evaluate(operand, visitedMethods, intermediateVars);

        if (eval.isEmpty()) {
            // Treat it as an unknown part, skip combining
            return;
        }

        for (String val : eval) {
            combineOperandsWithDeps(
                operands,
                index + 1,
                current + val,
                resultValues,
                visitedMethods,
                intermediateVars
            );
        }
    }

    private static @NotNull Set<String> resolveEnumFieldAccess(
        @NotNull UQualifiedReferenceExpression qualifiedExpr,
        @NotNull Set<PsiMethod> visitedMethods,
        @NotNull Map<String, Set<String>> intermediateVars
    ) {
        Set<String> result = new HashSet<>();
        UExpression receiver = qualifiedExpr.getReceiver();
        String selectorName = qualifiedExpr.getResolvedName();

        if (receiver instanceof UQualifiedReferenceExpression receiverQualified) {
            PsiElement baseResolved = receiverQualified.resolve();

            if (baseResolved instanceof PsiEnumConstant enumConst && selectorName != null) {
                PsiClass enumClass = enumConst.getContainingClass();
                if (enumClass == null) return result;

                PsiField targetField = enumClass.findFieldByName(selectorName, false);
                if (targetField == null) return result;

                PsiMethod constructor = enumConst.resolveConstructor();
                if (constructor == null || enumConst.getArgumentList() == null) return result;

                PsiExpression[] args = enumConst.getArgumentList().getExpressions();
                PsiCodeBlock body = constructor.getBody();
                if (body == null) return result;

                for (int i = 0; i < args.length; i++) {
                    for (PsiStatement statement : body.getStatements()) {
                        if (statement instanceof PsiExpressionStatement exprStmt &&
                            exprStmt.getExpression() instanceof PsiAssignmentExpression assignExpr) {
                            PsiExpression lhs = assignExpr.getLExpression();
                            PsiExpression rhs = assignExpr.getRExpression();
                            PsiParameter param = constructor.getParameterList().getParameters()[i];

                            if (lhs instanceof PsiReferenceExpression leftRef &&
                                (rhs instanceof PsiReferenceExpression ref && ref.resolve() == param) && // Right side matches param
                                leftRef.getReferenceName() != null &&
                                leftRef.getReferenceName().equals(targetField.getName())) {
                                UExpression argUExpr = UastContextKt.toUElement(args[i], UExpression.class);

                                if (argUExpr != null)
                                    result.addAll(evaluate(argUExpr, visitedMethods, intermediateVars));

                                break;
                            }
                        }
                    }
                }
            }
        }

        return result;
    }

}
