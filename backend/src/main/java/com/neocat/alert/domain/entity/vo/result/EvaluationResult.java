package com.neocat.alert.domain.entity.vo.result;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

import com.neocat.alert.domain.entity.vo.cursor.AlertEvalCursor;

/**
 * 一次分钟判定的结果。
 */
@NamedInterface("alert")
@Getter
@EqualsAndHashCode
@ToString
@AllArgsConstructor
public class EvaluationResult {

    private final boolean triggered;

    private final AlertEvalCursor cursor;

}
