package com.neocat.analysis.domain.analyzer;

import com.neocat.ingest.domain.tree.MessageTree;

/**
 * 单个分析域（PRD 02 §9）。
 *
 * <p>实现约定：
 * <ul>
 *   <li>幂等友好：同一棵树可能因重试再次进入，但 ingest 已做幂等，这里不重复处理同一 messageId；</li>
 *   <li>自包含：只处理自己的域，不调用其他 Analyzer；</li>
 *   <li>异常自限：抛出异常由 {@link RealtimeConsumer} 捕获，只影响本域。</li>
 * </ul>
 */
@org.springframework.modulith.NamedInterface("analysis")
public interface Analyzer {

    /** 分析域名称，用于失败记录与观测。 */
    String domain();

    void analyze(MessageTree tree);
}
