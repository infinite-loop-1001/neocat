package com.neocat.dashboard.domain.formula;

import java.util.Objects;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 解析结果。
 */
@NamedInterface("dashboard")
@Getter
@EqualsAndHashCode
@ToString
public class ParseOutcome {
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
