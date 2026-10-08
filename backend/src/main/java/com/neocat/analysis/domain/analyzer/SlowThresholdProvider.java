package com.neocat.analysis.domain.analyzer;
import org.springframework.modulith.NamedInterface;

/**
 * 慢阈值供应者（PRD 03 §9、PRD 05 §10）。
 *
 * <p>由 platform 模块提供；analysis 只依赖该抽象。
 * 阈值变更只影响此后创建的分析器，因此天然不重算历史。
 */
@NamedInterface("analysis")
public interface SlowThresholdProvider {

    int urlMs();

    int sqlMs();

    int callMs();

    int cacheMs();
}
