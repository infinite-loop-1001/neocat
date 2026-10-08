package com.neocat.query.domain.report;
import org.springframework.modulith.NamedInterface;

/**
 * 依赖查询方向（PRD 04 §8）。
 *
 * <p>与 {@code analysis} 域的 {@code DependencyDirection} 是两个独立类型：
 * 前者是分析期写序列时使用的方向，后者是查询期对已有序列的读方向。
 * 二者取值相同但分属不同边界，不互相引用。
 */
@NamedInterface("query")
public enum DependencyDirectionQuery {
    UPSTREAM,
    DOWNSTREAM
}
