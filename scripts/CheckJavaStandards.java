import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import javax.tools.ToolProvider;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;

/** 仅解析语法树，不做依赖解析或生成代码；精确检查成员（含注释、注解、嵌套类与多行初始化）。 */
public final class CheckJavaStandards {
    private CheckJavaStandards() {
    }

    private static boolean isNullLiteral(ExpressionTree expression) {
        while (expression instanceof ParenthesizedTree parentheses) {
            expression = parentheses.getExpression();
        }
        return expression.getKind() == Tree.Kind.NULL_LITERAL;
    }

    public static void main(String[] args) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (Objects.isNull(compiler)) throw new IllegalStateException("编码规范检查需要 JDK，不支持仅 JRE");
        try (var manager = compiler.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8)) {
            var inputs = manager.getJavaFileObjectsFromStrings(Arrays.asList(args));
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            var task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null, inputs);
            var trees = Trees.instance(task);
            int[] failures = {0};
            for (var unit : task.parse()) {
                String source = Files.readString(Path.of(unit.getSourceFile().toUri()));
                // 无 classpath 的语法树检查无法解析类型；用同文件内变量声明的类型把 String 与容器区分开。
                var declaredTypes = new java.util.HashMap<String, String>();
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitVariable(VariableTree variable, Void unused) {
                        if (Objects.nonNull(variable.getType())) {
                            declaredTypes.put(variable.getName().toString(), variable.getType().toString());
                        }
                        return super.visitVariable(variable, unused);
                    }
                }.scan(unit, null);
                boolean staticCollectorImport = unit.getImports().stream().anyMatch(i -> i.isStatic()
                        && i.getQualifiedIdentifier().toString().matches("java\\.util\\.stream\\.Collectors\\.(toMap|toConcurrentMap|\\*)"));
                new TreePathScanner<Void, Void>() {
                    private void error(long position, String message) {
                        failures[0]++;
                        System.err.println(Path.of(unit.getSourceFile().toUri()) + ":"
                                + unit.getLineMap().getLineNumber(position) + " " + message);
                    }

                    private String text(Tree tree) {
                        var positions = trees.getSourcePositions();
                        return source.substring((int) positions.getStartPosition(unit, tree),
                                (int) positions.getEndPosition(unit, tree));
                    }

                    /** 取 {@code Objects.isNull/isNonNull(x)} 的 x 文本，否则 null。 */
                    private String nullCheckArgument(Tree tree, String method) {
                        if (!(tree instanceof MethodInvocationTree call)) return null;
                        if (!call.getMethodSelect().toString().endsWith("Objects." + method) || call.getArguments().size() != 1) return null;
                        return text(call.getArguments().get(0));
                    }

                    /** 取 {@code x.isEmpty()} 的 x 文本，否则 null。 */
                    private String emptyCallTarget(Tree tree) {
                        if (!(tree instanceof MethodInvocationTree call)) return null;
                        if (!(call.getMethodSelect() instanceof com.sun.source.tree.MemberSelectTree select)) return null;
                        if (!Objects.equals(select.getIdentifier().toString(), "isEmpty") || !call.getArguments().isEmpty()) return null;
                        return text(select.getExpression());
                    }

                    /** String.isEmpty() 不属于容器判空规则；无法判定类型时放弃以免误报。 */
                    private boolean isStringTarget(String target) {
                        if (target.indexOf('.') >= 0 || target.indexOf('(') >= 0) return false;
                        String declared = declaredTypes.get(target);
                        return Objects.nonNull(declared) && declared.matches("(?:java\\.lang\\.)?String");
                    }

                    @Override
                    public Void visitBinary(BinaryTree binary, Void unused) {
                        if ((binary.getKind() == Tree.Kind.EQUAL_TO || binary.getKind() == Tree.Kind.NOT_EQUAL_TO)
                                && (isNullLiteral(binary.getLeftOperand()) || isNullLiteral(binary.getRightOperand()))) {
                            error(trees.getSourcePositions().getStartPosition(unit, binary),
                                    "Java 判空必须使用 Objects.isNull / Objects.nonNull，禁止直接比较 null");
                        }
                        // 容器判空组合只出现在容器/Map 上，应改用 Apache 工具类。
                        if (binary.getKind() == Tree.Kind.CONDITIONAL_OR) {
                            String target = nullCheckArgument(binary.getLeftOperand(), "isNull");
                            if (Objects.nonNull(target) && Objects.equals(target, emptyCallTarget(binary.getRightOperand()))
                                    && !isStringTarget(target)) {
                                error(trees.getSourcePositions().getStartPosition(unit, binary),
                                        "容器判空必须使用 CollectionUtils / MapUtils.isEmpty，禁止 Objects.isNull 与 isEmpty 组合");
                            }
                        }
                        if (binary.getKind() == Tree.Kind.CONDITIONAL_AND
                                && binary.getRightOperand() instanceof com.sun.source.tree.UnaryTree unary
                                && unary.getKind() == Tree.Kind.LOGICAL_COMPLEMENT) {
                            String target = nullCheckArgument(binary.getLeftOperand(), "nonNull");
                            if (Objects.nonNull(target) && Objects.equals(target, emptyCallTarget(unary.getExpression()))
                                    && !isStringTarget(target)) {
                                error(trees.getSourcePositions().getStartPosition(unit, binary),
                                        "容器判空必须使用 CollectionUtils / MapUtils.isNotEmpty，禁止 Objects.nonNull 与 !isEmpty 组合");
                            }
                        }
                        // size() 与 0 比较属于手写判空。
                        if (Objects.equals(binary.getKind(), Tree.Kind.EQUAL_TO) || Objects.equals(binary.getKind(), Tree.Kind.GREATER_THAN)) {
                            boolean sizeLeft = Objects.nonNull(sizeCall(binary.getLeftOperand())) && isZeroLiteral(binary.getRightOperand());
                            boolean sizeRight = Objects.nonNull(sizeCall(binary.getRightOperand())) && isZeroLiteral(binary.getLeftOperand());
                            if (sizeLeft || sizeRight) {
                                error(trees.getSourcePositions().getStartPosition(unit, binary),
                                        "容器判空必须使用 CollectionUtils / MapUtils，禁止 size() 与 0 比较");
                            }
                        }
                        return super.visitBinary(binary, unused);
                    }

                    private String sizeCall(Tree tree) {
                        if (!(tree instanceof MethodInvocationTree call)) return null;
                        if (!(call.getMethodSelect() instanceof com.sun.source.tree.MemberSelectTree select)) return null;
                        return Objects.equals(select.getIdentifier().toString(), "size") ? text(select.getExpression()) : null;
                    }

                    private boolean isZeroLiteral(Tree tree) {
                        return tree instanceof com.sun.source.tree.LiteralTree literal && Objects.equals(literal.getValue(), 0);
                    }

                    @Override
                    public Void visitMethod(MethodTree method, Void unused) {
                        if (Objects.nonNull(method.getReturnType()) && method.getReturnType().toString()
                                .matches("(?:java\\.util\\.)?Optional\\s*<.*>")) {
                            error(trees.getSourcePositions().getStartPosition(unit, method), "自有函数不返回 Optional，明确制定未找到语义");
                        }
                        return super.visitMethod(method, unused);
                    }

                    @Override
                    public Void visitClass(ClassTree type, Void unused) {
                        if (Objects.equals(type.getKind().name(), "RECORD")) {
                            error(trees.getSourcePositions().getStartPosition(unit, type), "禁止声明 record");
                        }
                        VariableTree previous = null;
                        for (var member : type.getMembers()) {
                            if (!(member instanceof VariableTree field)) {
                                previous = null;
                                continue;
                            }
                            // 枚举值不是需要单独空行的成员变量。
                            if (Objects.isNull(field.getType()) || Objects.equals(type.getKind().name(), "ENUM")
                                    && Objects.nonNull(field.getInitializer()) && Objects.equals(field.getInitializer().getKind().name(), "NEW_CLASS")) {
                                previous = null;
                                continue;
                            }
                            if (Objects.nonNull(previous)) {
                                long end = trees.getSourcePositions().getEndPosition(unit, previous);
                                long start = trees.getSourcePositions().getStartPosition(unit, field);
                                if (start < end || !source.substring((int) end, (int) start).matches("(?s).*\\R[\\t ]*\\R.*")) {
                                    error(start, "相邻成员字段必须空一行（注释不代替空行）");
                                }
                            }
                            previous = field;
                        }
                        return super.visitClass(type, unused);
                    }

                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
                        String method = call.getMethodSelect().toString();
                        boolean collector = method.matches("(?:java\\.util\\.stream\\.)?Collectors\\.(?:<.*>)?(toMap|toConcurrentMap)")
                                || staticCollectorImport && method.matches("(toMap|toConcurrentMap)");
                        if (collector && call.getArguments().size() < 3) {
                            error(trees.getSourcePositions().getStartPosition(unit, call), "Collector 必须显式提供重复 key 合并策略");
                        }
                        return super.visitMethodInvocation(call, unused);
                    }
                }.scan(unit, null);
            }
            for (var diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                    failures[0]++;
                    System.err.println(diagnostic);
                }
            }
            if (failures[0] > 0) System.exit(1);
        }
    }
}
