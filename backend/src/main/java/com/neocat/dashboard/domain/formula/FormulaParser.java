package com.neocat.dashboard.domain.formula;

import com.neocat.query.domain.stat.Stat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 公式解析与单位校验（PRD 05 §4，技术方案 02 §9.2）。
 *
 * <p>递归下降解析；文法：
 * <pre>
 * expr   := term (('+'|'-') term)*
 * term   := factor (('*'|'/') factor)*
 * factor := 'sum'|'avg'|'min'|'max' '(' stat ')' | stat | number | '(' expr ')'
 * </pre>
 *
 * <p>只接受文法规定的 token。任何未识别符号（如 {@code >}、{@code ;}、{@code .}、
 * 未知函数名、未知统计项）都会以 {@code FORMULA_INVALID} 拒绝，
 * 从而在解析层就阻断「自由脚本、条件表达式、跨服务公式」等不支持形式。
 *
 * <p>单位校验在解析过程中同步进行：加减两侧单位不兼容即返回 {@code UNIT_MISMATCH}，
 * 因此「不兼容的公式不能保存」由本类保证。
 */
@org.springframework.modulith.NamedInterface("dashboard")
public class FormulaParser {

    public static final String FORMULA_INVALID = com.neocat.common.error.ErrorCode.FORMULA_INVALID.name();

    public static final String UNIT_MISMATCH = com.neocat.common.error.ErrorCode.UNIT_MISMATCH.name();

    /**
     * 解析结果。
     */
    @org.springframework.modulith.NamedInterface("dashboard")
    @lombok.Getter
    @lombok.EqualsAndHashCode
    @lombok.ToString
    public static class ParseOutcome {
        private final Formula formula;

        private final String error;

        public ParseOutcome(Formula formula, String error) {
            this.formula = formula;
            this.error = error;
        }


        public boolean valid() {
            return Objects.nonNull(formula) && Objects.isNull(error);
        }

        public static ParseOutcome ok(Formula formula) {
            return new ParseOutcome(formula, null);
        }

        public static ParseOutcome fail(String error) {
            return new ParseOutcome(null, error);
        }
    }
    public ParseOutcome parse(String expression) {
        if (Objects.isNull(expression) || expression.isBlank()) {
            return ParseOutcome.fail(FORMULA_INVALID);
        }
        try {
            Cursor cursor = new Cursor(tokenize(expression));
            Formula formula = parseExpression(cursor);
            if (!cursor.exhausted()) {
                // 存在多余 token：例如 "hits hits" 或 "hits; failures"
                return ParseOutcome.fail(FORMULA_INVALID);
            }
            return ParseOutcome.ok(formula);
        } catch (InvalidFormulaException e) {
            return ParseOutcome.fail(FORMULA_INVALID);
        } catch (UnitMismatchException e) {
            return ParseOutcome.fail(UNIT_MISMATCH);
        }
    }
    /** 对已解析的公式复核单位（解析期已校验，此处供外部单独调用）。 */
    public String validateUnits(Formula formula) {
        if (Objects.isNull(formula)) {
            return FORMULA_INVALID;
        }
        try {
            validate(formula);
            return null;
        } catch (UnitMismatchException e) {
            return UNIT_MISMATCH;
        }
    }

    // ── 解析 ─────────────────────────────────────────────────

    private Formula parseExpression(Cursor cursor) {
        Formula left = parseTerm(cursor);
        while (cursor.accept("+") || cursor.accept("-")) {
            String op = cursor.previous();
            Formula right = parseTerm(cursor);
            Formula.Binary binary = new Formula.Binary(
                    Objects.equals("+", op) ? FormulaOperator.ADD : FormulaOperator.SUBTRACT, left, right);
            requireAdditiveCompatibility(binary);
            left = binary;
        }
        return left;
    }
    private Formula parseTerm(Cursor cursor) {
        Formula left = parseFactor(cursor);
        while (cursor.accept("*") || cursor.accept("/")) {
            String op = cursor.previous();
            Formula right = parseFactor(cursor);
            left = new Formula.Binary(
                    Objects.equals("*", op) ? FormulaOperator.MULTIPLY : FormulaOperator.DIVIDE, left, right);
        }
        return left;
    }
    private Formula parseFactor(Cursor cursor) {
        if (cursor.accept("(")) {
            Formula inner = parseExpression(cursor);
            if (!cursor.accept(")")) {
                throw new InvalidFormulaException();
            }
            return inner;
        }
        String token = cursor.peek();
        if (Objects.isNull(token)) {
            throw new InvalidFormulaException();
        }
        if (isAggregate(token) && cursor.peekNextIs("(")) {
            cursor.next();
            if (!cursor.accept("(")) {
                throw new InvalidFormulaException();
            }
            String statToken = cursor.next();
            Stat stat = tryParseStat(statToken);
            if (Objects.isNull(stat)) {
                throw new InvalidFormulaException();
            }
            if (!cursor.accept(")")) {
                // 多参数或未闭合都被拒绝，避免跨 Name / 跨服务公式
                throw new InvalidFormulaException();
            }
            return new Formula.Aggregate(aggregateOf(token), stat);
        }
        if (isNumber(token)) {
            cursor.next();
            return new Formula.Constant(Double.parseDouble(token));
        }
        Stat stat = tryParseStat(token);
        if (Objects.isNull(stat)) {
            throw new InvalidFormulaException();
        }
        cursor.next();
        return new Formula.Ref(stat);
    }

    // ── 单位校验 ─────────────────────────────────────────────

    private void validate(Formula formula) {
        if (formula instanceof Formula.Binary binary) {
            validate(binary.getLeft());
            validate(binary.getRight());
            if (binary.getOp() == FormulaOperator.ADD || binary.getOp() == FormulaOperator.SUBTRACT) {
                requireAdditiveCompatibility(binary);
            }
        }
    }
    /** 加减要求单位兼容，否则保存被拒。 */
    private void requireAdditiveCompatibility(Formula.Binary binary) {
        Unit left = binary.getLeft().unit();
        Unit right = binary.getRight().unit();
        if (!left.compatibleWith(right)) {
            throw new UnitMismatchException();
        }
    }

    // ── 词法与工具 ───────────────────────────────────────────

    private List<String> tokenize(String expression) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        String normalized = expression.trim();
        while (i < normalized.length()) {
            char c = normalized.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '+' || c == '-' || c == '*' || c == '/' || c == '(' || c == ')') {
                tokens.add(String.valueOf(c));
                i++;
                continue;
            }
            if (Character.isLetterOrDigit(c) || c == '_') {
                int start = i;
                while (i < normalized.length()) {
                    char d = normalized.charAt(i);
                    if (Character.isLetterOrDigit(d) || d == '_') {
                        i++;
                    } else {
                        break;
                    }
                }
                tokens.add(normalized.substring(start, i));
                continue;
            }
            // 未识别字符（> ; . " ' { } 等）：直接拒绝
            throw new InvalidFormulaException();
        }
        return tokens;
    }
    private boolean isAggregate(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return Objects.equals(lower, "sum") || Objects.equals(lower, "avg") || Objects.equals(lower, "min") || Objects.equals(lower, "max");
    }
    private FormulaAggregate aggregateOf(String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "sum" -> FormulaAggregate.SUM;
            case "avg" -> FormulaAggregate.AVG;
            case "min" -> FormulaAggregate.MIN;
            case "max" -> FormulaAggregate.MAX;
            default -> throw new InvalidFormulaException();
        };
    }
    private Stat tryParseStat(String token) {
        if (Objects.isNull(token)) {
            return null;
        }
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "hits" -> Stat.HITS;
            case "failures" -> Stat.FAILURES;
            case "failurerate" -> Stat.FAILURE_RATE;
            case "qps" -> Stat.QPS;
            case "avgduration" -> Stat.AVG;
            case "min" -> Stat.MIN;
            case "max" -> Stat.MAX;
            case "tp50" -> Stat.TP50;
            case "tp90" -> Stat.TP90;
            case "tp95" -> Stat.TP95;
            case "tp99" -> Stat.TP99;
            case "tp999" -> Stat.TP999;
            case "tp9999" -> Stat.TP9999;
            default -> null;
        };
    }
    private boolean isNumber(String token) {
        if (Objects.isNull(token) || token.isEmpty()) {
            return false;
        }
        char first = token.charAt(0);
        return Character.isDigit(first) || (first == '.' && token.length() > 1);
    }
    private static class InvalidFormulaException extends RuntimeException {
    }
    private static class UnitMismatchException extends RuntimeException {
    }
    /** 简单的 token 游标。 */
    private static class Cursor {
        private final List<String> tokens;

        private int index;

        private String previous;

        Cursor(List<String> tokens) {
            this.tokens = tokens;
        }

        String peek() {
            return index < tokens.size() ? tokens.get(index) : null;
        }

        boolean peekNextIs(String expected) {
            return index + 1 < tokens.size() && Objects.equals(tokens.get(index + 1), expected);
        }

        String next() {
            String token = peek();
            if (Objects.isNull(token)) {
                throw new InvalidFormulaException();
            }
            previous = token;
            index++;
            return token;
        }

        boolean accept(String expected) {
            if (Objects.equals(expected, peek())) {
                next();
                return true;
            }
            return false;
        }

        String previous() {
            return previous;
        }

        boolean exhausted() {
            return index >= tokens.size();
        }
    }
}

