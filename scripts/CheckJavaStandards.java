import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
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
import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.UnaryTree;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.util.TreePath;

/** 仅解析语法树，不做依赖解析或生成代码；精确检查成员（含注释、注解、嵌套类与多行初始化）。 */
public final class CheckJavaStandards {
    private CheckJavaStandards() {
    }

    private static boolean isNullLiteral(ExpressionTree expression) {
        while (expression instanceof ParenthesizedTree parentheses) {
            expression = parentheses.getExpression();
        }
        return Objects.equals(expression.getKind(), Tree.Kind.NULL_LITERAL);
    }

    private static Tree unwrapped(Tree tree) {
        while (tree instanceof ParenthesizedTree parentheses) {
            tree = parentheses.getExpression();
        }
        return tree;
    }

    /** 注解简单名，如 {@code Tag}、{@code Operation}、{@code Schema}。 */
    private static String annotationName(AnnotationTree annotation) {
        String text = annotation.getAnnotationType().toString();
        return text.substring(text.lastIndexOf('.') + 1);
    }

    /** 取注解的字符串字面量属性；缺失或非字面量返回 null。 */
    private static String annotationText(AnnotationTree annotation, String key) {
        for (var argument : annotation.getArguments()) {
            if (argument instanceof AssignmentTree assignment
                    && Objects.equals(assignment.getVariable().toString(), key)
                    && assignment.getExpression() instanceof LiteralTree literal) {
                return Objects.isNull(literal.getValue()) ? null : literal.getValue().toString();
            }
        }
        return null;
    }

    private static boolean hasAnnotation(ModifiersTree modifiers, String simpleName) {
        return modifiers.getAnnotations().stream()
                .anyMatch(annotation -> Objects.equals(annotationName(annotation), simpleName));
    }

    private static AnnotationTree annotation(ModifiersTree modifiers, String simpleName) {
        return modifiers.getAnnotations().stream()
                .filter(item -> Objects.equals(annotationName(item), simpleName))
                .findFirst().orElse(null);
    }

    /** 只登记实际枚举声明，不通过全大写名称猜测常量类型。 */
    private static Map<String, Set<String>> enumConstants(List<CompilationUnitTree> units) {
        Map<String, Set<String>> constants = new HashMap<>();
        constants.put("com.sun.source.tree.Tree.Kind",
                new HashSet<>(Arrays.stream(Tree.Kind.values()).map(Enum::name).toList()));
        constants.put("javax.tools.Diagnostic.Kind",
                new HashSet<>(Arrays.stream(Diagnostic.Kind.values()).map(Enum::name).toList()));
        for (var unit : units) {
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitClass(ClassTree type, Void unused) {
                    if (Objects.equals(type.getKind(), Tree.Kind.ENUM)) {
                        Set<String> names = new HashSet<>();
                        for (var member : type.getMembers()) {
                            if (member instanceof VariableTree field
                                    && field.getInitializer() instanceof NewClassTree
                                    && Objects.nonNull(field.getType())
                                    && Objects.equals(field.getType().toString(), type.getSimpleName().toString())) {
                                names.add(field.getName().toString());
                            }
                        }
                        constants.put(className(unit, getCurrentPath()), names);
                    }
                    return super.visitClass(type, unused);
                }
            }.scan(unit, null);
        }
        return constants;
    }

    private static String className(CompilationUnitTree unit, TreePath path) {
        List<String> names = new ArrayList<>();
        for (; Objects.nonNull(path); path = path.getParentPath()) {
            if (path.getLeaf() instanceof ClassTree type) {
                names.add(0, type.getSimpleName().toString());
            }
        }
        String packageName = Objects.isNull(unit.getPackageName()) ? "" : unit.getPackageName() + ".";
        return packageName + String.join(".", names);
    }

    public static void main(String[] args) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (Objects.isNull(compiler)) throw new IllegalStateException("编码规范检查需要 JDK，不支持仅 JRE");
        try (var manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var inputs = manager.getJavaFileObjectsFromStrings(Arrays.asList(args));
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            var task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null, inputs);
            var trees = Trees.instance(task);
            int[] failures = {0};
            List<CompilationUnitTree> units = new ArrayList<>();
            task.parse().forEach(units::add);
            var enumConstants = enumConstants(units);
            Map<String, String> operationIds = new HashMap<>();
            for (var unit : units) {
                String source = Files.readString(Path.of(unit.getSourceFile().toUri()));
                String filePath = Path.of(unit.getSourceFile().toUri()).toString().replace('\\', '/');
                boolean backendProduction = filePath.contains("/backend/src/main/java/");
                boolean handwrittenProduction = backendProduction || filePath.contains("/client-java/src/main/java/");
                String packageName = Objects.isNull(unit.getPackageName()) ? "" : unit.getPackageName().toString();
                boolean timeImplementation = Objects.equals(packageName, "com.neocat.common.time.clock")
                        && filePath.endsWith("/TimeProvider.java");
                var staticWallTimeImports = unit.getImports().stream().filter(i -> i.isStatic())
                        .map(i -> i.getQualifiedIdentifier().toString())
                        .filter(i -> i.matches("java\\.time\\.(?:Instant|LocalDate|LocalDateTime|ZonedDateTime|OffsetDateTime|OffsetTime|LocalTime)\\.(?:now|\\*)")
                                || i.matches("java\\.lang\\.System\\.(?:currentTimeMillis|\\*)")
                                || i.matches("java\\.time\\.Clock\\.(?:system|systemUTC|systemDefaultZone|\\*)"))
                        .toList();
                // 无 classpath 的语法树检查无法解析类型；用同文件内变量声明的类型把 String 与容器区分开。
                var declaredTypes = new HashMap<String, String>();
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
                boolean apiHttpSource = backendProduction && filePath.contains("/api/http/");
                boolean httpDtoSource = apiHttpSource && filePath.contains("/api/http/dto/");
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
                    private String nullCheckArgument(Tree tree, String method) {                        if (!(tree instanceof MethodInvocationTree call)) return null;
                        if (!call.getMethodSelect().toString().endsWith("Objects." + method) || call.getArguments().size() != 1) return null;
                        return text(call.getArguments().get(0));
                    }

                    /** 取 {@code x.isEmpty()} 的 x 文本，否则 null。 */
                    private String emptyCallTarget(Tree tree) {
                        if (!(tree instanceof MethodInvocationTree call)) return null;
                        if (!(call.getMethodSelect() instanceof MemberSelectTree select)) return null;
                        if (!Objects.equals(select.getIdentifier().toString(), "isEmpty") || !call.getArguments().isEmpty()) return null;
                        return text(select.getExpression());
                    }

                    /** String.isEmpty() 不属于容器判空规则；无法判定类型时放弃以免误报。 */
                    private boolean isStringTarget(String target) {
                        if (target.indexOf('.') >= 0 || target.indexOf('(') >= 0) return false;
                        String declared = declaredTypes.get(target);
                        return Objects.nonNull(declared) && declared.matches("(?:java\\.lang\\.)?String");
                    }

                    /** 无 classpath：仅检查可由枚举声明与 import 确认的常量，其余交给类型检查。 */
                    private boolean isEnumOperand(Tree operand) {
                        Tree tree = unwrapped(operand);
                        if (tree instanceof IdentifierTree identifier) {
                            String name = identifier.getName().toString();
                            String declared = declaredTypes.get(name);
                            if (Objects.nonNull(declared) && !resolvedEnumTypes(declared).isEmpty()) return true;
                            for (TreePath path = getCurrentPath(); Objects.nonNull(path); path = path.getParentPath()) {
                                if (path.getLeaf() instanceof ClassTree type) {
                                    if (!Objects.equals(type.getKind(), Tree.Kind.ENUM)) return false;
                                    Set<String> names = enumConstants.get(className(unit, path));
                                    return Objects.equals(name, "this") || Objects.nonNull(names) && names.contains(name)
                                            && Objects.equals(declaredTypes.get(name), type.getSimpleName().toString());
                                }
                            }
                            return false;
                        }
                        if (!(tree instanceof MemberSelectTree select)) return false;
                        String owner = select.getExpression().toString();
                        // 名字被变量遮蔽时不把变量字段当作类型常量。
                        if (declaredTypes.containsKey(owner.split("\\.")[0])) return false;
                        return resolvedEnumTypes(owner).stream()
                                .anyMatch(candidate -> enumConstants.get(candidate).contains(select.getIdentifier().toString()));
                    }

                    private Set<String> resolvedEnumTypes(String owner) {
                        Set<String> candidates = new HashSet<>();
                        candidates.add(owner);
                        candidates.add((packageName.isEmpty() ? "" : packageName + ".") + owner);
                        for (var imported : unit.getImports()) {
                            if (imported.isStatic()) continue;
                            String qualified = imported.getQualifiedIdentifier().toString();
                            if (qualified.endsWith(".*")) {
                                candidates.add(qualified.substring(0, qualified.length() - 1) + owner);
                            } else {
                                String simple = qualified.substring(qualified.lastIndexOf('.') + 1);
                                if (Objects.equals(owner, simple) || owner.startsWith(simple + ".")) {
                                    candidates.add(qualified + owner.substring(simple.length()));
                                }
                            }
                        }
                        for (TreePath path = getCurrentPath(); Objects.nonNull(path); path = path.getParentPath()) {
                            if (path.getLeaf() instanceof ClassTree) {
                                candidates.add(className(unit, path) + "." + owner);
                            }
                        }
                        candidates.removeIf(candidate -> !enumConstants.containsKey(candidate));
                        return candidates;
                    }

                    @Override
                    public Void visitVariable(VariableTree variable, Void unused) {
                        if (backendProduction && !timeImplementation && Objects.nonNull(variable.getType())
                                && variable.getType().toString().matches("(?:java\\.time\\.)?Clock|(?:com\\.neocat\\.common\\.time\\.clock\\.)?ClockProvider")) {
                            error(trees.getSourcePositions().getStartPosition(unit, variable),
                                    "生产代码禁止持有或注入 Clock / ClockProvider，使用公共静态 TimeProvider");
                        }
                        if (backendProduction && packageName.startsWith("com.neocat.common")
                                && variable.getModifiers().getAnnotations().stream()
                                .anyMatch(a -> a.getAnnotationType().toString().endsWith("ApolloStaticValue"))) {
                            error(trees.getSourcePositions().getStartPosition(unit, variable),
                                    "领域动态配置禁止放入 common，迁移至所属模块 config 包");
                        }
                        return super.visitVariable(variable, unused);
                    }

                    @Override
                    public Void visitBinary(BinaryTree binary, Void unused) {
                        if ((Objects.equals(binary.getKind(), Tree.Kind.EQUAL_TO) || Objects.equals(binary.getKind(), Tree.Kind.NOT_EQUAL_TO))
                                && (isNullLiteral(binary.getLeftOperand()) || isNullLiteral(binary.getRightOperand()))) {
                            error(trees.getSourcePositions().getStartPosition(unit, binary),
                                    "Java 判空必须使用 Objects.isNull / Objects.nonNull，禁止直接比较 null");
                        }
                        // 对象与枚举相等必须使用 Objects.equals；只有原始类型保留 ==。
                        if (Objects.equals(binary.getKind(), Tree.Kind.EQUAL_TO) || Objects.equals(binary.getKind(), Tree.Kind.NOT_EQUAL_TO)) {
                            if (!isNullLiteral(binary.getLeftOperand()) && !isNullLiteral(binary.getRightOperand())
                                    && (isEnumOperand(binary.getLeftOperand()) || isEnumOperand(binary.getRightOperand()))) {
                                error(trees.getSourcePositions().getStartPosition(unit, binary),
                                        "对象与枚举相等必须使用 Objects.equals，禁止 == / !=");
                            }
                        }
                        // 容器判空组合只出现在容器/Map 上，应改用 Apache 工具类。
                        if (Objects.equals(binary.getKind(), Tree.Kind.CONDITIONAL_OR)) {
                            String target = nullCheckArgument(binary.getLeftOperand(), "isNull");
                            if (Objects.nonNull(target) && Objects.equals(target, emptyCallTarget(binary.getRightOperand()))
                                    && !isStringTarget(target)) {
                                error(trees.getSourcePositions().getStartPosition(unit, binary),
                                        "容器判空必须使用 CollectionUtils / MapUtils.isEmpty，禁止 Objects.isNull 与 isEmpty 组合");
                            }
                        }
                        if (Objects.equals(binary.getKind(), Tree.Kind.CONDITIONAL_AND)
                                && binary.getRightOperand() instanceof UnaryTree unary
                                && Objects.equals(unary.getKind(), Tree.Kind.LOGICAL_COMPLEMENT)) {
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
                        // 分钟点对齐属于公共时间能力，调用方不得手写 epoch 毫秒取整。
                        if (backendProduction && (Objects.equals(binary.getKind(), Tree.Kind.DIVIDE)
                                || Objects.equals(binary.getKind(), Tree.Kind.MULTIPLY))
                                && epochMillis(binary.getLeftOperand()) && isMinuteMillis(binary.getRightOperand())) {
                            error(trees.getSourcePositions().getStartPosition(unit, binary),
                                    "分钟点对齐禁止手写毫秒取整，使用 TimeProvider.delayedMinuteStart(delaySeconds)");
                        }
                        return super.visitBinary(binary, unused);
                    }

                    /** 左值为 epoch 毫秒读取（{@code toEpochMilli()} / {@code millis()}）。 */
                    private boolean epochMillis(Tree tree) {
                        return tree.toString().matches(".*\\.(?:toEpochMilli|millis)\\(\\)");
                    }

                    /** 一分钟的毫秒字面量，识别 60_000 / 60000 / 60 * 1000 的常见写法。 */
                    private boolean isMinuteMillis(Tree tree) {
                        if (tree instanceof LiteralTree literal) {
                            return Objects.equals(literal.getValue(), 60_000) || Objects.equals(literal.getValue(), 60_000L);
                        }
                        if (tree instanceof BinaryTree product && Objects.equals(product.getKind(), Tree.Kind.MULTIPLY)) {
                            return isLiteralValue(product.getLeftOperand(), 60) && isLiteralValue(product.getRightOperand(), 1_000);
                        }
                        return false;
                    }

                    private boolean isLiteralValue(Tree tree, int expected) {
                        return tree instanceof LiteralTree literal && Objects.equals(literal.getValue(), expected);
                    }

                    private String sizeCall(Tree tree) {
                        if (!(tree instanceof MethodInvocationTree call)) return null;
                        if (!(call.getMethodSelect() instanceof MemberSelectTree select)) return null;
                        return Objects.equals(select.getIdentifier().toString(), "size") ? text(select.getExpression()) : null;
                    }

                    private boolean isZeroLiteral(Tree tree) {
                        return tree instanceof LiteralTree literal && Objects.equals(literal.getValue(), 0);
                    }

                    @Override
                    public Void visitMethod(MethodTree method, Void unused) {
                        if (backendProduction && !timeImplementation && Objects.nonNull(method.getReturnType())
                                && method.getReturnType().toString().matches("(?:java\\.time\\.)?Clock|(?:com\\.neocat\\.common\\.time\\.clock\\.)?ClockProvider")) {
                            error(trees.getSourcePositions().getStartPosition(unit, method),
                                    "生产代码禁止提供 Clock Bean，使用公共静态 TimeProvider");
                        }
                        if (Objects.nonNull(method.getReturnType()) && method.getReturnType().toString()
                                .matches("(?:java\\.util\\.)?Optional\\s*<.*>")) {
                            error(trees.getSourcePositions().getStartPosition(unit, method), "自有函数不返回 Optional，明确制定未找到语义");
                        }
                        return super.visitMethod(method, unused);
                    }

                    /** 接口文档注解完整性（编码规范 http-api.md，强制）。 */
                    private void checkApiDocumentation(ClassTree type) {
                        if (!apiHttpSource) return;
                        var modifiers = type.getModifiers();
                        if (hasAnnotation(modifiers, "RestController")) {
                            AnnotationTree tag = annotation(modifiers, "Tag");
                            if (Objects.isNull(tag)) {
                                error(trees.getSourcePositions().getStartPosition(unit, type),
                                        "对外 Controller 必须有 @Tag(name=…, description=…)");
                            } else if (Objects.isNull(annotationText(tag, "name")) || annotationText(tag, "name").isBlank()
                                    || Objects.isNull(annotationText(tag, "description")) || annotationText(tag, "description").isBlank()) {
                                error(trees.getSourcePositions().getStartPosition(unit, type), "@Tag 的 name 与 description 不能为空");
                            }
                            checkMappingMethods(type);
                        }
                        if (httpDtoSource && !Objects.equals(type.getKind(), Tree.Kind.ENUM)) {
                            AnnotationTree schema = annotation(modifiers, "Schema");
                            if (Objects.isNull(schema)) {
                                error(trees.getSourcePositions().getStartPosition(unit, type),
                                        "HTTP DTO 类必须有 @Schema(description=…)");
                            } else if (Objects.isNull(annotationText(schema, "description"))
                                    || annotationText(schema, "description").isBlank()) {
                                error(trees.getSourcePositions().getStartPosition(unit, type), "@Schema 的 description 不能为空");
                            }
                        }
                    }

                    /** 每个映射方法必须有 @Operation，且 summary 与 operationId 完整、operationId 全局唯一。 */
                    private void checkMappingMethods(ClassTree type) {
                        for (var member : type.getMembers()) {
                            if (!(member instanceof MethodTree method)) continue;
                            AnnotationTree mapping = method.getModifiers().getAnnotations().stream()
                                    .filter(item -> annotationName(item).matches("[A-Z]\\w*Mapping"))
                                    .findFirst().orElse(null);
                            if (Objects.isNull(mapping)) continue;
                            AnnotationTree operation = annotation(method.getModifiers(), "Operation");
                            if (Objects.isNull(operation)) {
                                error(trees.getSourcePositions().getStartPosition(unit, method),
                                        "对外端点必须有 @Operation(summary=…, operationId=…)");
                                continue;
                            }
                            String summary = annotationText(operation, "summary");
                            if (Objects.isNull(summary) || summary.isBlank()) {
                                error(trees.getSourcePositions().getStartPosition(unit, method), "@Operation 的 summary 不能为空");
                            }
                            String operationId = annotationText(operation, "operationId");
                            if (Objects.isNull(operationId) || operationId.isBlank()) {
                                error(trees.getSourcePositions().getStartPosition(unit, method), "@Operation 的 operationId 不能为空");
                            } else {
                                String previous = operationIds.putIfAbsent(operationId, filePath);
                                if (Objects.nonNull(previous)) {
                                    error(trees.getSourcePositions().getStartPosition(unit, method),
                                            "@Operation 的 operationId 必须全局唯一，重复：" + operationId);
                                }
                            }
                        }
                    }

                    @Override
                    public Void visitClass(ClassTree type, Void unused) {
                        checkTypeFile(type);
                        boolean isEnum = Objects.equals(type.getKind(), Tree.Kind.ENUM);
                        if (Objects.equals(type.getKind().name(), "RECORD")) {
                            error(trees.getSourcePositions().getStartPosition(unit, type), "禁止声明 record");
                        }
                        checkApiDocumentation(type);
                        VariableTree previous = null;
                        for (var member : type.getMembers()) {
                            if (!(member instanceof VariableTree field)) {
                                previous = null;
                                continue;
                            }
                            // 枚举值不是需要单独空行的成员变量。
                            if (Objects.isNull(field.getType()) || isEnum
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

                    /** 私有实现、匿名类型和 SDK Builder 不适用独立文件规则。 */
                    private void checkTypeFile(ClassTree type) {
                        if (!handwrittenProduction || type.getSimpleName().length() == 0) return;
                        for (TreePath path = getCurrentPath(); Objects.nonNull(path); path = path.getParentPath()) {
                            if (path.getLeaf() instanceof MethodTree) return;
                            if (path.getLeaf() instanceof ClassTree owner
                                    && (owner.getSimpleName().length() == 0
                                    || owner.getModifiers().getFlags().contains(Modifier.PRIVATE))) return;
                        }
                        String qualified = className(unit, getCurrentPath());
                        if (Objects.equals(qualified, "com.neocat.client.NeoCat.Builder")
                                || qualified.startsWith("com.neocat.client.NeoCat.Builder.")) return;
                        Tree parent = getCurrentPath().getParentPath().getLeaf();
                        if (!(parent instanceof CompilationUnitTree)) {
                            error(trees.getSourcePositions().getStartPosition(unit, type),
                                    "具名生产类型必须拆为独立顶层类型（一种类型一个文件）");
                        } else if (!filePath.endsWith("/" + type.getSimpleName() + ".java")) {
                            error(trees.getSourcePositions().getStartPosition(unit, type),
                                    "顶层类型名必须与独立 Java 文件名一致");
                        }
                    }

                    @Override
                    public Void visitMemberReference(MemberReferenceTree reference, Void unused) {
                        String method = reference.getQualifierExpression() + "." + reference.getName();
                        if (backendProduction && !timeImplementation && isSystemWallTime(method)) {
                            error(trees.getSourcePositions().getStartPosition(unit, reference),
                                    "生产代码禁止直接读取系统墙上时间，使用 TimeProvider");
                        }
                        return super.visitMemberReference(reference, unused);
                    }

                    private boolean isSystemWallTime(String method) {
                        return method.matches("(?:java\\.time\\.)?(?:Instant|LocalDate|LocalDateTime|ZonedDateTime|OffsetDateTime|OffsetTime|LocalTime)\\.now")
                                || method.matches("(?:java\\.lang\\.)?System\\.currentTimeMillis")
                                || method.matches("(?:java\\.time\\.)?Clock\\.system(?:UTC|DefaultZone)?")
                                || staticWallTimeImports.stream().anyMatch(i ->
                                i.endsWith("." + method) || i.endsWith(".*") &&
                                (i.startsWith("java.time.Clock.") && method.matches("system|systemUTC|systemDefaultZone")
                                        || i.startsWith("java.lang.System.") && Objects.equals(method, "currentTimeMillis")
                                        || !i.startsWith("java.time.Clock.") && i.startsWith("java.time.") && Objects.equals(method, "now")));
                    }

                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
                        String method = call.getMethodSelect().toString();
                        if (backendProduction && !timeImplementation && isSystemWallTime(method)) {
                            error(trees.getSourcePositions().getStartPosition(unit, call),
                                    "生产代码禁止直接读取系统墙上时间，使用 TimeProvider");
                        }
                        if (method.matches("(?:java\\.util\\.)?(?:List|Set|Map)\\.(?:<.*>)?of") && call.getArguments().isEmpty()) {
                            error(trees.getSourcePositions().getStartPosition(unit, call),
                                    "空容器必须使用 Guava 可变工厂，禁止 List.of / Set.of / Map.of");
                        }
                        if (method.endsWith(".subList") && call.getArguments().size() == 2) {
                            error(trees.getSourcePositions().getStartPosition(unit, call),
                                    "源码中禁止使用 subList，必须创建独立集合");
                        }
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
                if (Objects.equals(diagnostic.getKind(), Diagnostic.Kind.ERROR)) {
                    failures[0]++;
                    System.err.println(diagnostic);
                }
            }
            if (failures[0] > 0) System.exit(1);
        }
    }
}
