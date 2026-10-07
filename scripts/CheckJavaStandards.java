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
                boolean staticCollectorImport = unit.getImports().stream().anyMatch(i -> i.isStatic()
                        && i.getQualifiedIdentifier().toString().matches("java\\.util\\.stream\\.Collectors\\.(toMap|toConcurrentMap|\\*)"));
                new TreePathScanner<Void, Void>() {
                    private void error(long position, String message) {
                        failures[0]++;
                        System.err.println(Path.of(unit.getSourceFile().toUri()) + ":"
                                + unit.getLineMap().getLineNumber(position) + " " + message);
                    }

                    @Override
                    public Void visitBinary(BinaryTree binary, Void unused) {
                        if ((binary.getKind() == Tree.Kind.EQUAL_TO || binary.getKind() == Tree.Kind.NOT_EQUAL_TO)
                                && (isNullLiteral(binary.getLeftOperand()) || isNullLiteral(binary.getRightOperand()))) {
                            error(trees.getSourcePositions().getStartPosition(unit, binary),
                                    "Java 判空必须使用 Objects.isNull / Objects.nonNull，禁止直接比较 null");
                        }
                        return super.visitBinary(binary, unused);
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
                        if (type.getKind().name().equals("RECORD")) {
                            error(trees.getSourcePositions().getStartPosition(unit, type), "禁止声明 record");
                        }
                        VariableTree previous = null;
                        for (var member : type.getMembers()) {
                            if (!(member instanceof VariableTree field)) {
                                previous = null;
                                continue;
                            }
                            // 枚举值不是需要单独空行的成员变量。
                            if (Objects.isNull(field.getType()) || type.getKind().name().equals("ENUM")
                                    && Objects.nonNull(field.getInitializer()) && field.getInitializer().getKind().name().equals("NEW_CLASS")) {
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
