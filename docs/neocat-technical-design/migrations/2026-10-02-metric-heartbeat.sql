-- Run manually before deploying the upgraded backend. Non-destructive, re-runnable.
-- Existing historical Heartbeat values cannot be reconstructed as last samples.
ALTER TABLE neocat.nc_week_bucket ADD COLUMN IF NOT EXISTS problem_category LowCardinality(String) DEFAULT '';
ALTER TABLE neocat.nc_month_bucket ADD COLUMN IF NOT EXISTS problem_category LowCardinality(String) DEFAULT '';
ALTER TABLE neocat.nc_minute_bucket ADD COLUMN IF NOT EXISTS value_count UInt64 DEFAULT 0;
ALTER TABLE neocat.nc_hour_bucket ADD COLUMN IF NOT EXISTS value_count UInt64 DEFAULT 0;
ALTER TABLE neocat.nc_day_bucket ADD COLUMN IF NOT EXISTS value_count UInt64 DEFAULT 0;
ALTER TABLE neocat.nc_week_bucket ADD COLUMN IF NOT EXISTS value_count UInt64 DEFAULT 0;
ALTER TABLE neocat.nc_month_bucket ADD COLUMN IF NOT EXISTS value_count UInt64 DEFAULT 0;
-- Preserve unknown observation counts through further rollups, including mixed old/new rows.
ALTER TABLE neocat.nc_minute_bucket ADD COLUMN IF NOT EXISTS value_count_missing UInt8 DEFAULT value_count = 0;
ALTER TABLE neocat.nc_hour_bucket ADD COLUMN IF NOT EXISTS value_count_missing UInt8 DEFAULT value_count = 0;
ALTER TABLE neocat.nc_day_bucket ADD COLUMN IF NOT EXISTS value_count_missing UInt8 DEFAULT value_count = 0;
ALTER TABLE neocat.nc_week_bucket ADD COLUMN IF NOT EXISTS value_count_missing UInt8 DEFAULT value_count = 0;
ALTER TABLE neocat.nc_month_bucket ADD COLUMN IF NOT EXISTS value_count_missing UInt8 DEFAULT value_count = 0;
ALTER TABLE neocat.nc_minute_bucket ADD COLUMN IF NOT EXISTS value_last Nullable(Float64) DEFAULT NULL;
ALTER TABLE neocat.nc_hour_bucket ADD COLUMN IF NOT EXISTS value_last Nullable(Float64) DEFAULT NULL;
ALTER TABLE neocat.nc_day_bucket ADD COLUMN IF NOT EXISTS value_last Nullable(Float64) DEFAULT NULL;
ALTER TABLE neocat.nc_week_bucket ADD COLUMN IF NOT EXISTS value_last Nullable(Float64) DEFAULT NULL;
ALTER TABLE neocat.nc_month_bucket ADD COLUMN IF NOT EXISTS value_last Nullable(Float64) DEFAULT NULL;
ALTER TABLE neocat.nc_minute_bucket ADD COLUMN IF NOT EXISTS value_last_time Nullable(DateTime64(3, 'UTC')) DEFAULT NULL;
ALTER TABLE neocat.nc_hour_bucket ADD COLUMN IF NOT EXISTS value_last_time Nullable(DateTime64(3, 'UTC')) DEFAULT NULL;
ALTER TABLE neocat.nc_day_bucket ADD COLUMN IF NOT EXISTS value_last_time Nullable(DateTime64(3, 'UTC')) DEFAULT NULL;
ALTER TABLE neocat.nc_week_bucket ADD COLUMN IF NOT EXISTS value_last_time Nullable(DateTime64(3, 'UTC')) DEFAULT NULL;
ALTER TABLE neocat.nc_month_bucket ADD COLUMN IF NOT EXISTS value_last_time Nullable(DateTime64(3, 'UTC')) DEFAULT NULL;
ALTER TABLE neocat.nc_hour_bucket ADD COLUMN IF NOT EXISTS covered_seconds UInt32 DEFAULT 3600;
ALTER TABLE neocat.nc_day_bucket ADD COLUMN IF NOT EXISTS covered_seconds UInt32 DEFAULT 86400;
ALTER TABLE neocat.nc_week_bucket ADD COLUMN IF NOT EXISTS covered_seconds UInt32 DEFAULT 604800;
ALTER TABLE neocat.nc_month_bucket ADD COLUMN IF NOT EXISTS covered_seconds UInt32 DEFAULT 2678400;

-- A source writes snapshots, not additive increments. A retry/updated snapshot from the same
-- writer+series+bucket must replace its earlier snapshot at query time (no dependency on merges).
ALTER TABLE neocat.nc_minute_bucket ADD COLUMN IF NOT EXISTS snapshot_source String DEFAULT '';
ALTER TABLE neocat.nc_hour_bucket ADD COLUMN IF NOT EXISTS snapshot_source String DEFAULT '';
ALTER TABLE neocat.nc_day_bucket ADD COLUMN IF NOT EXISTS snapshot_source String DEFAULT '';
ALTER TABLE neocat.nc_week_bucket ADD COLUMN IF NOT EXISTS snapshot_source String DEFAULT '';
ALTER TABLE neocat.nc_month_bucket ADD COLUMN IF NOT EXISTS snapshot_source String DEFAULT '';
ALTER TABLE neocat.nc_minute_bucket ADD COLUMN IF NOT EXISTS snapshot_version UInt64 DEFAULT 0;
ALTER TABLE neocat.nc_hour_bucket ADD COLUMN IF NOT EXISTS snapshot_version UInt64 DEFAULT 0;
ALTER TABLE neocat.nc_day_bucket ADD COLUMN IF NOT EXISTS snapshot_version UInt64 DEFAULT 0;
ALTER TABLE neocat.nc_week_bucket ADD COLUMN IF NOT EXISTS snapshot_version UInt64 DEFAULT 0;
ALTER TABLE neocat.nc_month_bucket ADD COLUMN IF NOT EXISTS snapshot_version UInt64 DEFAULT 0;

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
ALTER TABLE neocat.nc_metric_label_metadata ADD COLUMN IF NOT EXISTS source String DEFAULT '';
