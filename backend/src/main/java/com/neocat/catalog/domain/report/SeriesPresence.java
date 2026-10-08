package com.neocat.catalog.domain.report;
import org.springframework.modulith.NamedInterface;

/**
 * 序列存在性判定：服务/实例在「某报表类型 + 某时间范围」内是否有数据。
 *
 * <p>由 analysis/query 模块实现（当前小时读内存报表，历史范围读 ClickHouse 桶）。
 * catalog 只依赖该抽象，从而不反向依赖报表模块。
 */
@NamedInterface("catalog")
public interface SeriesPresence {

    boolean hasData(String serviceName, ReportKind kind, TimeRange range);

    boolean hasInstanceData(String serviceName, String instanceId, ReportKind kind, TimeRange range);

    /** 默认实现：一律判定为无数据（用于只需要目录能力的场景）。 */
    static SeriesPresence denyAll() {
        return new SeriesPresence() {
            @Override
            public boolean hasData(String serviceName, ReportKind kind, TimeRange range) {
                return false;
            }

            @Override
            public boolean hasInstanceData(String serviceName, String instanceId, ReportKind kind, TimeRange range) {
                return false;
            }
        };
    }
}
