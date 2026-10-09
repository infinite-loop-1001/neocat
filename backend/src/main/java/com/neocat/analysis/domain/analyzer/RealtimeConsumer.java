package com.neocat.analysis.domain.analyzer;

import com.neocat.ingest.domain.tree.MessageTree;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Component;
import com.neocat.analysis.domain.analyzer.result.DomainFailure;
import com.neocat.analysis.domain.analyzer.result.FanOutResult;

/**
 * 分析扇出调度（PRD 02 §9）。
 *
 * <p>关键行为：每个分析域独立 try/catch，因此
 * <ul>
 *   <li>单域失败（含 {@link Error}）不会中断其他域的处理；</li>
 *   <li>失败被记录为 {@link com.neocat.analysis.domain.analyzer.result.DomainFailure}，携带域、MessageTree ID 与原因；</li>
 *   <li>该域产生的缺口单独表达，其他域的数据照常产出；</li>
 *   <li>不做重放：丢弃即丢弃，避免放大故障（PRD 02 §8「丢弃数据不补算」）。</li>
 * </ul>
 */
@NamedInterface("analysis")
@Component
public class RealtimeConsumer {

    private final List<Analyzer> analyzers;

    public RealtimeConsumer(List<Analyzer> analyzers) {
        this.analyzers = List.copyOf(analyzers);
    }
    public FanOutResult consume(MessageTree tree) {
        List<String> succeeded = new ArrayList<>();
        List<DomainFailure> failures = new ArrayList<>();

        for (Analyzer analyzer : analyzers) {
            String domain = safeDomain(analyzer);
            try {
                analyzer.analyze(tree);
                succeeded.add(domain);
            } catch (Throwable t) {
                // 捕获 Throwable 而非 Exception：单个域的错误（含 OutOfMemoryError 之外
                // 的严重错误）不应拖垮其他域的采集。
                failures.add(new DomainFailure(
                        domain,
                        Objects.isNull(tree) ? null : tree.getMessageId(),
                        t.getClass().getSimpleName() + ": " + t.getMessage()));
            }
        }
        return new FanOutResult(succeeded, failures);
    }
    public List<Analyzer> analyzers() {
        return analyzers;
    }
    private String safeDomain(Analyzer analyzer) {
        try {
            return analyzer.domain();
        } catch (Throwable t) {
            return analyzer.getClass().getSimpleName();
        }
    }
}
