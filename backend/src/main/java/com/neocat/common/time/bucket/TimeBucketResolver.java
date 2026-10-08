package com.neocat.common.time.bucket;

import com.neocat.common.time.range.RangeQuick;
import com.neocat.common.time.range.RangeSpec;

import java.util.List;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.modulith.NamedInterface;

/**
 * 把 {@link RangeSpec} 解析为按平台时区对齐的时间桶序列。
 *
 * <p>契约（PRD 03 §2）：
 * <ul>
 *   <li>固定周期：小时→1 分钟/点；日→10 分钟/点；周→1 小时/点；月→1 自然日/点。</li>
 *   <li>快捷范围的默认粒度见 {@link RangeQuick}。</li>
 *   <li>滚动范围首尾部分桶保留，只计算落入实际查询范围的数据，并标记 partial。</li>
 *   <li>桶边界与平台时区的固定边界对齐。</li>
 * </ul>
 */
@NamedInterface("time")
public interface TimeBucketResolver {

    /**
     * @param spec 查询范围
     * @param zone 平台时区
     * @return 左闭右开的桶序列，按时间升序
     */
    List<Bucket> resolve(RangeSpec spec, ZoneId zone);

    /** 把某一时刻按其粒度对齐到桶起点。 */
    Instant alignStart(Instant instant, Granularity granularity, ZoneId zone);
}
