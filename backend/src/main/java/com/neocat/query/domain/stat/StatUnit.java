package com.neocat.query.domain.stat;
import org.springframework.modulith.NamedInterface;

/**
 * 统计项单位（技术方案 03 §4.2）。
 *
 * <p>用于大盘公式校验（COUNT / DURATION / RATE）。
 */
@NamedInterface("query")
public enum StatUnit {
    COUNT,
    DURATION,
    RATE
}
