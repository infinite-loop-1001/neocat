-- ============================================================================
-- NeoCat 报表与原始树存储（ClickHouse 24.x）
-- 用途：分钟/小时/日/周/月桶、原始 MessageTree、Trace 关系、依赖边、数据质量
-- 说明：不依赖 ReplacingMergeTree；同一 (key, bucket) 可多次写入，
--       新数据查询先按 writer/key/bucket 选最新快照，再合并不同 writer 的分子与分布。
--       旧 snapshot_source 为空的数据保留原增量语义，不可恢复的历史最后值为空。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS neocat;

-- ============================================================================
-- 1. 分钟桶（报表原子事实，所有上层聚合的唯一来源）
-- ============================================================================

CREATE TABLE IF NOT EXISTS neocat.nc_minute_bucket
(
    service       LowCardinality(String),
    kind          LowCardinality(String),   -- TRANSACTION / EVENT / PROBLEM / HEARTBEAT / METRIC / DEPENDENCY
    type          String,                   -- URL / SQL / CALL / CACHE / business / 指标名
    name          String,                   -- Transaction/Event/Problem 名称；METRIC 时为空串；DEPENDENCY 时为对端服务名
    instance      LowCardinality(String),   -- 'all' 表示全机器聚合行；否则为具体实例 ID
    problem_category LowCardinality(String) DEFAULT '',  -- EXCEPTION / SLOW_URL / SLOW_SQL / SLOW_CALL / SLOW_CACHE / ''
    metric_labels String DEFAULT '',         -- METRIC 专用：规范化标签串；被并入 other 时取字面量 '__OTHER__'；非 METRIC 为空

    minute        DateTime('UTC'),           -- 桶起点，左闭右开 [minute, minute+60)
    count         UInt64,                    -- Hits
    fail_count    UInt64,                    -- Failures
    duration_sum  UInt64,                    -- 毫秒总耗时
    duration_min  UInt64,
    duration_max  UInt64,
    value_sum     Float64 DEFAULT 0,         -- Metric 数值总和
    value_count   UInt64 DEFAULT 0,          -- 数值观测次数，不是一般调用 count
    value_count_missing UInt8 DEFAULT value_count = 0,
    value_last    Nullable(Float64) DEFAULT NULL,
    value_last_time Nullable(DateTime64(3, 'UTC')) DEFAULT NULL,
    snapshot_source String DEFAULT '',
    snapshot_version UInt64 DEFAULT 0,
    value_min     Float64 DEFAULT 0,
    value_max     Float64 DEFAULT 0,
    distribution  Array(UInt64),             -- 16 段耗时分布（或精确值时为空）
    exact_values  Array(UInt32),             -- 低基数时保留精确耗时值（≤ 200），保证分位准确
    covered_seconds UInt16 DEFAULT 60,       -- 桶实际覆盖秒数（部分覆盖桶 < 60）
    quality       LowCardinality(String) DEFAULT 'OK',  -- OK / ZERO / NO_DATA / DROPPED / MERGED_OTHER
    updated_at    DateTime DEFAULT now()
)
ENGINE = MergeTree
PARTITION BY toYYYYMMDD(minute)
ORDER BY (service, kind, type, name, instance, minute)
TTL toStartOfDay(minute) + INTERVAL 30 DAY DELETE
SETTINGS index_granularity = 8192;

-- ============================================================================
-- 2. 小时桶（分钟桶合并；QPS 分母固定 3600，当前小时除外）
-- ============================================================================

CREATE TABLE IF NOT EXISTS neocat.nc_hour_bucket
(
    service       LowCardinality(String),
    kind          LowCardinality(String),
    type          String,
    name          String,
    instance      LowCardinality(String),
    problem_category LowCardinality(String) DEFAULT '',
    metric_labels String DEFAULT '',
    hour          DateTime('UTC'),
    count         UInt64,
    fail_count    UInt64,
    duration_sum  UInt64,
    duration_min  UInt64,
    duration_max  UInt64,
    value_sum     Float64 DEFAULT 0,
    value_count   UInt64 DEFAULT 0,
    value_count_missing UInt8 DEFAULT value_count = 0,
    value_last    Nullable(Float64) DEFAULT NULL,
    value_last_time Nullable(DateTime64(3, 'UTC')) DEFAULT NULL,
    snapshot_source String DEFAULT '',
    snapshot_version UInt64 DEFAULT 0,
    covered_seconds UInt32 DEFAULT 3600,
    value_min     Float64 DEFAULT 0,
    value_max     Float64 DEFAULT 0,
    distribution  Array(UInt64),
    quality       LowCardinality(String) DEFAULT 'OK',
    updated_at    DateTime DEFAULT now()
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(hour)
ORDER BY (service, kind, type, name, instance, hour)
TTL toStartOfDay(hour) + INTERVAL 30 DAY DELETE
SETTINGS index_granularity = 8192;

-- ============================================================================
-- 3. 日 / 周 / 月桶（至少保留 13 个月）
-- ============================================================================

CREATE TABLE IF NOT EXISTS neocat.nc_day_bucket
(
    service       LowCardinality(String),
    kind          LowCardinality(String),
    type          String,
    name          String,
    instance      LowCardinality(String),
    problem_category LowCardinality(String) DEFAULT '',
    metric_labels String DEFAULT '',
    day           Date,
    count         UInt64,
    fail_count    UInt64,
    duration_sum  UInt64,
    duration_min  UInt64,
    duration_max  UInt64,
    value_sum     Float64 DEFAULT 0,
    value_count   UInt64 DEFAULT 0,
    value_count_missing UInt8 DEFAULT value_count = 0,
    value_last    Nullable(Float64) DEFAULT NULL,
    value_last_time Nullable(DateTime64(3, 'UTC')) DEFAULT NULL,
    snapshot_source String DEFAULT '',
    snapshot_version UInt64 DEFAULT 0,
    covered_seconds UInt32 DEFAULT 86400,
    value_min     Float64 DEFAULT 0,
    value_max     Float64 DEFAULT 0,
    distribution  Array(UInt64),
    updated_at    DateTime DEFAULT now()
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(day)
ORDER BY (service, kind, type, name, instance, day)
TTL day + INTERVAL 13 MONTH DELETE
SETTINGS index_granularity = 8192;

CREATE TABLE IF NOT EXISTS neocat.nc_week_bucket
(
    service       LowCardinality(String),
    kind          LowCardinality(String),
    type          String,
    name          String,
    instance      LowCardinality(String),
    problem_category LowCardinality(String) DEFAULT '',
    metric_labels String DEFAULT '',
    week_start    Date,                     -- 平台时区周一 00:00
    count         UInt64,
    fail_count    UInt64,
    duration_sum  UInt64,
    duration_min  UInt64,
    duration_max  UInt64,
    value_sum     Float64 DEFAULT 0,
    value_count   UInt64 DEFAULT 0,
    value_count_missing UInt8 DEFAULT value_count = 0,
    value_last    Nullable(Float64) DEFAULT NULL,
    value_last_time Nullable(DateTime64(3, 'UTC')) DEFAULT NULL,
    snapshot_source String DEFAULT '',
    snapshot_version UInt64 DEFAULT 0,
    covered_seconds UInt32 DEFAULT 604800,
    value_min     Float64 DEFAULT 0,
    value_max     Float64 DEFAULT 0,
    distribution  Array(UInt64),
    updated_at    DateTime DEFAULT now()
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(week_start)
ORDER BY (service, kind, type, name, instance, week_start)
TTL week_start + INTERVAL 13 MONTH DELETE
SETTINGS index_granularity = 8192;

CREATE TABLE IF NOT EXISTS neocat.nc_month_bucket
(
    service       LowCardinality(String),
    kind          LowCardinality(String),
    type          String,
    name          String,
    instance      LowCardinality(String),
    problem_category LowCardinality(String) DEFAULT '',
    metric_labels String DEFAULT '',
    month_start   Date,                     -- 平台时区月初 00:00
    count         UInt64,
    fail_count    UInt64,
    duration_sum  UInt64,
    duration_min  UInt64,
    duration_max  UInt64,
    value_sum     Float64 DEFAULT 0,
    value_count   UInt64 DEFAULT 0,
    value_count_missing UInt8 DEFAULT value_count = 0,
    value_last    Nullable(Float64) DEFAULT NULL,
    value_last_time Nullable(DateTime64(3, 'UTC')) DEFAULT NULL,
    snapshot_source String DEFAULT '',
    snapshot_version UInt64 DEFAULT 0,
    covered_seconds UInt32 DEFAULT 2678400,
    value_min     Float64 DEFAULT 0,
    value_max     Float64 DEFAULT 0,
    distribution  Array(UInt64),
    updated_at    DateTime DEFAULT now()
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(month_start)
ORDER BY (service, kind, type, name, instance, month_start)
TTL month_start + INTERVAL 13 MONTH DELETE
SETTINGS index_granularity = 8192;

-- ============================================================================
-- 4. Metric 小时排名与 other 合入（链路 21、PRD 04 §2-3）
-- ============================================================================

CREATE TABLE IF NOT EXISTS neocat.nc_metric_hour_rank
(
    service      LowCardinality(String),
    hour         DateTime('UTC'),
    metric_name  String,
    labels       String,                  -- 规范化标签串
    report_count UInt64,                  -- 该小时上报次数，用于排名
    promoted     UInt8 DEFAULT 0,         -- 1 = 进入前 1000，独立序列
    final_rank   UInt32 DEFAULT 0,        -- 排名固化后写入
    updated_at   DateTime DEFAULT now()
)
ENGINE = MergeTree
PARTITION BY toYYYYMMDD(hour)
ORDER BY (service, hour, metric_name, labels)
TTL toStartOfDay(hour) + INTERVAL 30 DAY DELETE
SETTINGS index_granularity = 8192;

-- ============================================================================
-- 5. 原始 MessageTree（保留 7 天；超期后汇总仍可查，但不可下钻）
-- ============================================================================

-- 实际 Metric 标签归属：与旧排名表分离，保存无歧义标签和 actual merged 标记。
CREATE TABLE IF NOT EXISTS neocat.nc_metric_label_metadata
(
    service String,
    metric_name String,
    hour DateTime('UTC'),
    labels String,
    labels_json String,
    merged UInt8,
    version UInt64,
    source String DEFAULT ''
)
ENGINE = MergeTree
PARTITION BY toYYYYMMDD(hour)
ORDER BY (service, metric_name, hour, labels)
TTL hour + INTERVAL 13 MONTH DELETE;

CREATE TABLE IF NOT EXISTS neocat.nc_raw_tree
(
    service           LowCardinality(String),
    instance          LowCardinality(String),
    message_id        String,               -- 全局唯一，树级幂等键
    root_message_id   String,               -- 跨服务 Trace 根
    parent_message_id String DEFAULT '',    -- 上游树
    tree_timestamp    DateTime64(3, 'UTC'), -- 用于迟到判定与 Trace 过期计算
    fingerprint       FixedString(64),      -- sha256(规范化树内容)，用于幂等冲突判定
    payload           String,               -- 序列化树（Protobuf bytes 的 base64 或 zstd）
    ingested_at       DateTime DEFAULT now()
)
ENGINE = MergeTree
PARTITION BY toYYYYMMDD(tree_timestamp)
ORDER BY (root_message_id, tree_timestamp, message_id)
TTL toStartOfDay(tree_timestamp) + INTERVAL 7 DAY DELETE
SETTINGS index_granularity = 8192;

-- ============================================================================
-- 6. Trace 关系（父子/根关系索引，支撑跨服务组装与缺失表达）
-- ============================================================================

CREATE TABLE IF NOT EXISTS neocat.nc_trace_relation
(
    message_id        String,
    root_message_id   String,
    parent_message_id String DEFAULT '',
    service           LowCardinality(String),
    instance          LowCardinality(String),
    tree_timestamp    DateTime64(3, 'UTC'),
    root_span_start   DateTime64(3, 'UTC'),  -- 跨服务耗时计算锚点
    status            LowCardinality(String) DEFAULT 'OK'  -- OK / HAS_FAILURE
)
ENGINE = MergeTree
PARTITION BY toYYYYMMDD(tree_timestamp)
ORDER BY (root_message_id, tree_timestamp, message_id)
TTL toStartOfDay(tree_timestamp) + INTERVAL 7 DAY DELETE
SETTINGS index_granularity = 8192;

-- ============================================================================
-- 7. 数据质量事件（缺数/丢弃/冲突/过期/域失败，驱动 quality 标记）
-- ============================================================================

CREATE TABLE IF NOT EXISTS neocat.nc_quality_event
(
    event_time DateTime('UTC'),
    event_type LowCardinality(String),  -- EXPIRED / ID_CONFLICT / QUEUE_FULL / MALFORMED / DOMAIN_FAILURE
    service    LowCardinality(String) DEFAULT '',
    message_id String DEFAULT '',
    detail     String DEFAULT '',
    count      UInt64 DEFAULT 1         -- 同分钟内同类事件聚合计数
)
ENGINE = MergeTree
PARTITION BY toYYYYMMDD(event_time)
ORDER BY (event_type, service, event_time)
TTL toStartOfDay(event_time) + INTERVAL 30 DAY DELETE
SETTINGS index_granularity = 8192;

-- ============================================================================
-- 8. 上报统计（接收量/丢弃量/队列水位，用于容量观测与降级决策）
-- ============================================================================

CREATE TABLE IF NOT EXISTS neocat.nc_ingest_stat
(
    minute           DateTime('UTC'),
    instance         LowCardinality(String),
    accepted         UInt64,
    duplicate        UInt64,
    dropped          UInt64,
    rejected         UInt64,
    queue_watermark  UInt32,
    consumer_lag_ms  UInt32
)
ENGINE = MergeTree
PARTITION BY toYYYYMMDD(minute)
ORDER BY (instance, minute)
TTL toStartOfDay(minute) + INTERVAL 30 DAY DELETE
SETTINGS index_granularity = 8192;

-- ============================================================================
-- 9. 聚合语句模板（由应用以参数化查询执行，不在建表脚本中直接运行）
--    参数：{from:DateTime} {to:DateTime}
--    不变式：sum / min / max 与分布数组逐元素相加；avg 与分位在查询期由合并结果计算。
-- ============================================================================

-- 9.1 分钟 → 小时（整点后 2 分钟执行）
-- INSERT INTO neocat.nc_hour_bucket
--     (service, kind, type, name, instance, problem_category, metric_labels, hour,
--      count, fail_count, duration_sum, duration_min, duration_max,
--      value_sum, value_min, value_max, distribution)
-- SELECT
--     service, kind, type, name, instance, problem_category, metric_labels,
--     toStartOfHour(minute)  AS hour,
--     sum(count)             AS count,
--     sum(fail_count)        AS fail_count,
--     sum(duration_sum)      AS duration_sum,
--     min(duration_min)      AS duration_min,
--     max(duration_max)      AS duration_max,
--     sum(value_sum)         AS value_sum,
--     min(value_min)         AS value_min,
--     max(value_max)         AS value_max,
--     arrayMap(i -> sum(arrayElement(distribution, i)), range(1, 17)) AS distribution
-- FROM neocat.nc_minute_bucket
-- WHERE minute >= {from:DateTime} AND minute < {to:DateTime}
-- GROUP BY service, kind, type, name, instance, problem_category, metric_labels, hour;

-- 9.2 小时 → 日（每日 00:05，取前一日 24 个小时桶）
-- SELECT ..., toDate(hour) AS day, arrayMap(...) FROM neocat.nc_hour_bucket
-- WHERE hour >= {from:DateTime} AND hour < {to:DateTime}
-- GROUP BY service, kind, type, name, instance, problem_category, metric_labels, day;

-- 9.3 小时 → 周（周一 00:10，周起点为平台时区周一 00:00）；小时 → 月（每月 1 日 00:15）
-- SELECT ..., toStartOfWeek(hour, 1) AS week_start, arrayMap(...) FROM neocat.nc_hour_bucket ...
-- SELECT ..., toStartOfMonth(hour)   AS month_start, arrayMap(...) FROM neocat.nc_hour_bucket ...

-- ============================================================================
-- 10. 查询语句模板（应用侧参数化；分位与 QPS 的正确形态）
-- ============================================================================

-- 10.0 源表选择：按**请求粒度**选表，与范围长度无关（技术方案 03 §4.2）。
--      粒度 ≥ 1 天      → nc_day_bucket
--      粒度 = 1 小时    → nc_hour_bucket
--      粒度 ≤ 20 分钟   → nc_minute_bucket，并在读侧折叠到目标粒度
--      折叠时按目标桶起点归并，分子与分布数组都要相加；
--      桶起点必须锚定目标桶起点（不是「第一个有数据的源桶」），
--      否则调用方按桶起点取数会对不上。
--      注意：日桶表的时间列是 Date，读回时按平台时区当地 00:00 还原为 Instant，
--      不要用驱动的默认时区解释，否则横轴会整体偏移一天。

-- 10.1 桶内分位：先合并分布数组，再在应用层（Java）按累计权重估算分位。
--      查询只负责把 16 段分布逐段相加后取回；禁止平均子桶分位。
--      参数：{service} {type} {name} {from} {to}
SELECT
    minute,
    sum(count)                                     AS hits,
    sum(fail_count)                                AS failures,
    if(sum(count) = 0, NULL, sum(duration_sum) / sum(count))   AS avg_duration,
    if(sum(count) = 0, NULL, sum(fail_count) / sum(count))     AS failure_rate,
    sum(count) / sum(covered_seconds)                           AS qps,
    arrayMap(i -> sum(arrayElement(distribution, i)), range(1, 17)) AS merged_distribution
FROM neocat.nc_minute_bucket
WHERE service = {service:String}
  AND kind = 'TRANSACTION' AND type = {type:String} AND name = {name:String}
  AND instance = 'all'
  AND minute >= {from:DateTime} AND minute < {to:DateTime}
GROUP BY minute
ORDER BY minute;

-- 10.1.1 分位由应用层 com.neocat.core.report.DurationDistribution#percentile 计算：
--        对数分箱第 i 段覆盖 [2^i, 2^(i+1)) ms（i = 0..15，即 1ms–65536ms），
--        段内按累计权重线性插值。该实现有 Spock 单测覆盖，不依赖 ClickHouse 版本能力。

-- 10.2 范围 QPS（CAT 口径）：分子为范围内总次数，分母为该报表实际覆盖秒数
--      当前小时：coveredSeconds = 整点至 now 的秒数；完整历史小时：3600；其他：实际覆盖
SELECT
    sum(count)                                    AS hits,
    if(sum(count) = 0, NULL, sum(duration_sum) / sum(count)) AS avg_duration,
    sum(count) / {coveredSeconds:UInt32}          AS qps_cat_semantics
FROM neocat.nc_minute_bucket
WHERE service = {service:String} AND kind = 'TRANSACTION'
  AND minute >= {from:DateTime} AND minute < {to:DateTime};

-- 10.3 Metric 具体序列在某小时被并入 other 时返回缺口（不得用 other 值冒充）
SELECT labels, promoted, report_count
FROM neocat.nc_metric_hour_rank
WHERE service = {service:String} AND hour = {hour:DateTime}
  AND metric_name = {metric:String} AND labels = {labels:String};

-- 10.4 依赖上下游（含下游树缺失的边仍计入，因为依赖来自调用方观测）
SELECT type AS downstream_service, sum(count) AS calls, sum(fail_count) AS failures,
       if(sum(count) = 0, NULL, sum(duration_sum) / sum(count)) AS avg_duration
FROM neocat.nc_minute_bucket
WHERE kind = 'DEPENDENCY' AND service = {service:String}
  AND minute >= {from:DateTime} AND minute < {to:DateTime}
GROUP BY type ORDER BY calls DESC;

-- 10.5 服务目录：当前类型 + 当前范围有数据的服务（动态过滤，无数据不展示）
SELECT DISTINCT service
FROM neocat.nc_minute_bucket
WHERE kind = {kind:String} AND minute >= {from:DateTime} AND minute < {to:DateTime}
  AND quality IN ('OK', 'ZERO');

-- 10.6 机器视图 TopN + other 对账（数量类/速率类应与总量一致）
SELECT
    if(row_number() OVER (ORDER BY sum(count) DESC) <= {topN:UInt32}, instance, 'other') AS bucket_instance,
    sum(count) AS hits
FROM neocat.nc_minute_bucket
WHERE service = {service:String} AND kind = 'TRANSACTION' AND instance != 'all'
  AND minute >= {from:DateTime} AND minute < {to:DateTime}
GROUP BY instance, bucket_instance
ORDER BY hits DESC;

-- ============================================================================
-- 11. 人工自检
-- ============================================================================
-- SHOW TABLES FROM neocat;
-- SELECT count() FROM neocat.nc_minute_bucket;
-- SELECT name, expr FROM system.tables WHERE database = 'neocat' AND name LIKE 'nc_%';
