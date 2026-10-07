-- ============================================================================
-- NeoCat 配置与元数据库（MySQL 8.0）
-- 用途：账号/会话/组织/大盘/告警规则/慢阈值/通道/服务实例目录/平台档案
-- 不存放：报表明细、原始 MessageTree（见 06-clickhouse-schema.sql）
-- 字符集：utf8mb4 / utf8mb4_0900_ai_ci；存储引擎 InnoDB
--
-- 约束策略：**本库不使用外键约束**。引用完整性由应用在同一事务内显式维护：
--   - 删除叶子：organization 服务按序清理资源投影 → 成员 → 有效叶子 → 节点本身；
--   - 删除大盘：按 阈值线 → 卡片 → 大盘 顺序删除；
--   - 删除卡片：先删阈值线；
--   - 删除规则：按 条件 → 收件人 → 窗口状态 → 规则 顺序删除。
-- 因此**删除路径不能只删父行**，否则会留下悬挂引用；对应删除用例见
-- docs/development/integration-test-runbook.md 的悬挂查询判据。
-- ============================================================================

CREATE
    DATABASE IF NOT EXISTS neocat
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE
    neocat;

-- ============================================================================
-- 0. 平台档案与运行参数（单行表）
-- ============================================================================

-- 稳定业务范围锁键：不要为每次请求/每个分钟创建无限增长的锁行。
CREATE TABLE nc_distributed_lock
(
    lock_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    PRIMARY KEY (lock_key)
) ENGINE = InnoDB;

CREATE TABLE nc_platform_profile
(
    id             TINYINT     NOT NULL DEFAULT 1 COMMENT '恒为 1，单行表',
    initialized    TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否已完成初始化',
    timezone       VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai' COMMENT '平台固定时区，初始化后只读',
    slow_url_ms    INT         NOT NULL DEFAULT 1000 COMMENT '慢 URL 阈值（PRD 默认 1000ms）',
    slow_sql_ms    INT         NOT NULL DEFAULT 100 COMMENT '慢 SQL 阈值（默认 100ms）',
    slow_call_ms   INT         NOT NULL DEFAULT 1000 COMMENT '慢调用阈值（默认 1000ms）',
    slow_cache_ms  INT         NOT NULL DEFAULT 50 COMMENT '慢缓存阈值（默认 50ms）',
    initialized_at DATETIME(3) NULL COMMENT '初始化时刻',
    updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT ck_nc_platform_profile_single CHECK (id = 1)
) ENGINE = InnoDB COMMENT = '平台档案：时区、慢阈值、初始化状态';

CREATE TABLE nc_channel_config
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    channel     VARCHAR(16) NOT NULL COMMENT 'EMAIL / DINGTALK / FEISHU',
    enabled     TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '未启用的通道不可在告警规则中选择',
    config_json JSON        NULL COMMENT 'SMTP/Webhook 等凭据（一期明文 JSON，不入代码库）',
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_nc_channel_config_channel (channel)
) ENGINE = InnoDB COMMENT = '通知通道配置（PRD 一期：邮件/钉钉/飞书，初始为空）';

-- ============================================================================
-- 1. identity：账号、角色、会话、访问历史
-- ============================================================================

CREATE TABLE nc_account
(
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    username             VARCHAR(64)  NOT NULL COMMENT '用户名唯一，一期不可修改',
    password_hash        VARCHAR(100) NOT NULL COMMENT 'BCrypt',
    role                 VARCHAR(16)  NOT NULL DEFAULT 'USER' COMMENT 'USER / ADMIN / SUPER_ADMIN',
    status               VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED / DISABLED',
    must_change_password TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '首次登录/重置后必须改密',
    created_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_nc_account_username (username),
    KEY idx_nc_account_status (status)
) ENGINE = InnoDB COMMENT = '账号（一期不支持删除账号与改用户名）';

CREATE TABLE nc_session
(
    id           CHAR(36)    NOT NULL COMMENT '会话 ID（Cookie: NC_SESSION）',
    account_id   BIGINT      NOT NULL,
    expires_at   DATETIME(3) NOT NULL COMMENT '滑动 30 分钟，每次有效请求续期',
    last_seen_at DATETIME(3) NOT NULL,
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_nc_session_account (account_id),
    KEY idx_nc_session_expires (expires_at)
) ENGINE = InnoDB COMMENT = '登录会话（允许多会话并存；登出只注销当前会话）';

CREATE TABLE nc_access_history
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    account_id   BIGINT       NOT NULL,
    service_name VARCHAR(256) NOT NULL,
    accessed_at  DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_nc_access_history_account_service (account_id, service_name),
    KEY idx_nc_access_history_time (account_id, accessed_at DESC)
) ENGINE = InnoDB COMMENT = '最近访问服务：决定登录后落到某服务 Transaction 还是服务列表';

-- ============================================================================
-- 2. organization：组织树、成员、有效叶子（权限即时重算）
-- ============================================================================

CREATE TABLE nc_org_node
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    name       VARCHAR(128) NOT NULL COMMENT '同一父节点下名称唯一',
    parent_id  BIGINT       NULL COMMENT 'NULL 表示根节点；一期不支持移动节点',
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_nc_org_node_parent_name (parent_id, name),
    KEY idx_nc_org_node_parent (parent_id)
) ENGINE = InnoDB COMMENT = '组织树（多根；叶子才能拥有大盘）';

CREATE TABLE nc_org_member
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    org_id     BIGINT      NOT NULL,
    account_id BIGINT      NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_nc_org_member_org_account (org_id, account_id),
    KEY idx_nc_org_member_account (account_id)
) ENGINE = InnoDB COMMENT = '组织直接成员（有效叶子 = 直系 + 祖先继承）';

-- 派生表：任何成员关系变更后即时重算（祖先继承展开），避免查询期递归
CREATE TABLE nc_effective_leaf
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    account_id  BIGINT      NOT NULL,
    org_id      BIGINT      NOT NULL COMMENT '该用户有权限的叶子组织',
    computed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_nc_effective_leaf_account_org (account_id, org_id),
    KEY idx_nc_effective_leaf_org (org_id)
) ENGINE = InnoDB COMMENT = '有效叶子权限快照（成员变更即时重算；删除叶子即时失效）';

-- ============================================================================
-- 3. catalog：服务与实例自动发现目录
-- ============================================================================

CREATE TABLE nc_service
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    name          VARCHAR(256) NOT NULL COMMENT '上报 serviceName；平台不提供手工建服务',
    first_seen_at DATETIME(3)  NOT NULL,
    last_seen_at  DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_nc_service_name (name)
) ENGINE = InnoDB COMMENT = '服务目录（首次合法上报即发现，即使随后队列满被丢）';

CREATE TABLE nc_instance
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    service_name  VARCHAR(256) NOT NULL,
    instance_id   VARCHAR(256) NOT NULL COMMENT '通常为 IP，产品不强制格式',
    first_seen_at DATETIME(3)  NOT NULL,
    last_seen_at  DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_nc_instance_service_instance (service_name, instance_id)
) ENGINE = InnoDB COMMENT = '实例目录；展示时按当前报表类型+时间范围动态过滤';

-- ============================================================================
-- 4. dashboard：叶子大盘、卡片、公式、阈值线
-- ============================================================================

CREATE TABLE nc_dashboard
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    org_id     BIGINT       NOT NULL COMMENT '必须是叶子组织；一期无个人大盘',
    name       VARCHAR(128) NOT NULL,
    order_no   INT          NOT NULL DEFAULT 0,
    created_by BIGINT       NULL,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_nc_dashboard_org (org_id)
) ENGINE = InnoDB COMMENT = '叶子组织大盘（删除叶子时级联删除）';

CREATE TABLE nc_card
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    dashboard_id     BIGINT       NOT NULL,
    service          VARCHAR(256) NOT NULL COMMENT '卡片目标：一个服务',
    target_kind      VARCHAR(16)  NOT NULL COMMENT 'TRANSACTION / EVENT / PROBLEM / METRIC / HEARTBEAT',
    target_type      VARCHAR(128) NULL COMMENT '如 URL / SQL / business',
    target_name      VARCHAR(256) NULL COMMENT 'Transaction/Event/Problem 名称',
    metric_name      VARCHAR(256) NULL COMMENT 'target_kind=METRIC 时的指标名',
    metric_labels    JSON         NULL COMMENT 'target_kind=METRIC 时的标签组合（规范化后）',
    heartbeat_metric VARCHAR(32)  NULL COMMENT 'heap-used / gc-count / threads 等',
    instance_scope   JSON         NULL COMMENT '维度范围；NULL 表示全部机器聚合',
    formula          VARCHAR(512) NOT NULL DEFAULT 'sum(hits)' COMMENT '聚合与四则运算表达式',
    formula_unit     VARCHAR(16)  NOT NULL COMMENT 'COUNT / DURATION / RATE / RATIO，保存期校验',
    time_range       VARCHAR(32)  NOT NULL DEFAULT 'RECENT_24H',
    order_no         INT          NOT NULL DEFAULT 0,
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_nc_card_dashboard (dashboard_id, order_no),
    KEY idx_nc_card_target (service, target_kind, target_type, target_name)
) ENGINE = InnoDB COMMENT = '大盘卡片：一张卡片只能绑定【一个服务 + 一个指标对象】';

CREATE TABLE nc_card_threshold_line
(
    id        BIGINT         NOT NULL AUTO_INCREMENT,
    card_id   BIGINT         NOT NULL,
    direction VARCHAR(8)     NOT NULL COMMENT 'ABOVE / BELOW',
    value     DECIMAL(20, 6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_nc_card_threshold_card (card_id)
) ENGINE = InnoDB COMMENT = '阈值线：纯视觉，不自动改变关联告警规则的比较阈值';

-- ============================================================================
-- 5. alert：规则、条件、收件人、窗口状态
-- ============================================================================

CREATE TABLE nc_alert_rule
(
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    scope                VARCHAR(16)   NOT NULL COMMENT 'SERVICE / ORGANIZATION',
    org_id               BIGINT        NULL COMMENT '组织告警固定该叶子；服务告警为 NULL',
    name                 VARCHAR(128)  NOT NULL,
    description          VARCHAR(512)  NULL,
    target_kind          VARCHAR(16)   NOT NULL COMMENT 'RAW_METRIC / CARD_RESULT',
    report_kind          VARCHAR(16)   NOT NULL COMMENT 'TRANSACTION / EVENT / PROBLEM / METRIC / HEARTBEAT',
    target_service       VARCHAR(256)  NOT NULL,
    target_type          VARCHAR(128)  NULL,
    target_name          VARCHAR(256)  NULL,
    target_metric_name   VARCHAR(256)  NULL,
    target_metric_labels VARCHAR(1024) NULL COMMENT '规范化 Metric 标签串',
    formula_stats        VARCHAR(512)  NULL COMMENT '卡片结果公式引用统计项，逗号分隔',
    target_stat          VARCHAR(32)   NOT NULL COMMENT '首个条件统计项，如 FAILURE_RATE / HITS / TP99',
    channels             VARCHAR(64)   NOT NULL COMMENT '规则通道选择，逗号分隔；收件人为空时仍须保留',
    target_card_id       BIGINT        NULL COMMENT '组织告警引用卡片结果时的卡片 ID',
    combinator           VARCHAR(4)    NOT NULL DEFAULT 'AND' COMMENT '整条规则统一 AND 或统一 OR',
    window_points        INT           NOT NULL DEFAULT 1 COMMENT '整条规则共用的滑动窗口长度 X（完整分钟点）',
    enabled              TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '保存后始终为 0，必须手动启用',
    invalid              TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '目标失效（如卡片被删）但保留配置',
    state_since          DATETIME(3)   NULL COMMENT '启用时刻；窗口只使用该时刻之后的完整分钟点',
    created_by           BIGINT        NULL,
    created_at           DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at           DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_nc_alert_rule_scope (scope, org_id),
    KEY idx_nc_alert_rule_enabled (enabled),
    KEY idx_nc_alert_rule_card (target_card_id),
    KEY idx_nc_alert_rule_service (target_service, target_kind, target_type, target_name)
) ENGINE = InnoDB COMMENT = '告警规则：一条规则只绑定一个目标时间序列；不保存任何触发历史';

CREATE TABLE nc_alert_condition
(
    id         BIGINT         NOT NULL AUTO_INCREMENT,
    rule_id    BIGINT         NOT NULL,
    stat       VARCHAR(32)    NOT NULL COMMENT 'HITS / FAILURES / FAILURE_RATE / QPS / AVG / TP99 ...',
    comparator VARCHAR(8)     NOT NULL COMMENT 'GT / GTE / LT / LTE / EQ / NEQ',
    threshold  DECIMAL(20, 6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_nc_alert_condition_rule (rule_id)
) ENGINE = InnoDB COMMENT = '比较条件；连续点数 X 只属于规则，不能逐条件配置';

CREATE TABLE nc_alert_recipient
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    rule_id    BIGINT      NOT NULL,
    account_id BIGINT      NOT NULL,
    channel    VARCHAR(16) NOT NULL COMMENT 'EMAIL / DINGTALK / FEISHU',
    PRIMARY KEY (id),
    UNIQUE KEY uk_nc_alert_recipient_rule_account_channel (rule_id, account_id, channel),
    KEY idx_nc_alert_recipient_account (account_id)
) ENGINE = InnoDB COMMENT = '收件人与通道；账号禁用/失去成员资格时自动移除，规则本身不自动关闭';

CREATE TABLE nc_alert_window_state
(
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    rule_id      BIGINT      NOT NULL,
    point_minute DATETIME(3) NOT NULL COMMENT '完整分钟点（平台时区对齐）',
    satisfied    TINYINT(1)  NOT NULL COMMENT '该点条件组合结果；缺数点不写入（视为打断）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_nc_alert_window_state_rule_minute (rule_id, point_minute)
) ENGINE = InnoDB COMMENT = '滑动窗口状态；启用/编辑/补人时清空，从零重建';

-- ============================================================================
-- 6. 组织资源同步投影与初始化数据
-- Organization-owned synchronous resource projection. Writers update this in the same
-- MySQL transaction as nc_dashboard/nc_alert_rule; deletion refuses an inconsistent view.
CREATE TABLE nc_org_resource
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    org_id        BIGINT       NOT NULL,
    resource_kind VARCHAR(16)  NOT NULL,
    resource_id   BIGINT       NOT NULL,
    name          VARCHAR(128) NOT NULL DEFAULT '',
    card_count    BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_nc_org_resource_org_kind_resource (org_id, resource_kind, resource_id)
) ENGINE = InnoDB COMMENT = 'Organization resource projection';

-- Reconcile existing installations on migration; the table is empty for new installs.
# INSERT INTO nc_org_resource (org_id, resource_kind, resource_id, name, card_count)
# SELECT d.org_id, 'DASHBOARD', d.id, d.name, COUNT(c.id)
# FROM nc_dashboard d
#          LEFT JOIN nc_card c ON c.dashboard_id = d.id
# GROUP BY d.org_id, d.id, d.name
# ON DUPLICATE KEY
#     UPDATE name       =
#                VALUES(name),
#            card_count =
#                VALUES(card_count);
#
# INSERT INTO nc_org_resource (org_id, resource_kind, resource_id, name)
# SELECT org_id, 'ALERT_RULE', id, ''
# FROM nc_alert_rule
# WHERE org_id IS NOT NULL
# ON DUPLICATE KEY
#     UPDATE resource_id =
#                VALUES(resource_id);

-- 初始化数据
-- ============================================================================

-- 6.1 平台档案单行：未初始化。首次启动时应用读取 neocat.platform.init.* 完成初始化，
--     写入超管（BCrypt 哈希在运行期生成，不把固定哈希写进 SQL）与默认慢阈值，
--     并把 initialized 置 1。
INSERT INTO nc_platform_profile (id, initialized, timezone, slow_url_ms, slow_sql_ms, slow_call_ms, slow_cache_ms)
VALUES (1, 0, 'Asia/Shanghai', 1000, 100, 1000, 50)
ON DUPLICATE KEY
    UPDATE id = id;

-- 6.2 通道：初始为空（PRD 要求"空的邮件、钉钉、飞书通道配置"）
INSERT INTO nc_channel_config (channel, enabled, config_json)
VALUES ('EMAIL', 0, NULL),
       ('DINGTALK', 0, NULL),
       ('FEISHU', 0, NULL)
ON DUPLICATE KEY
    UPDATE channel = channel;

-- 6.3 组织/大盘/组织告警：初始为空（PRD 要求"无组织、无大盘、无组织告警的初始状态"）
--     不插入任何 nc_org_node / nc_dashboard / nc_card / nc_alert_rule 数据。

-- ============================================================================
-- 7. 运维参考查询（不创建视图）
-- ============================================================================
-- 本库不创建任何 VIEW：应用不使用视图，且链路 9/10 的“叶子是否有资源”判据
-- 已改为读取 nc_org_resource 投影并在不一致时拒绝放行（fail-closed）。
-- 若再定义一个直接读源表的视图，会与应用的判据分叉，DBA 排查时得到误导结论。
-- 需要时按下方示例直接执行只读查询即可。

-- 7.1 叶子组织（无子节点）
-- SELECT n.id, n.name, n.parent_id
-- FROM nc_org_node n
-- WHERE NOT EXISTS (SELECT 1 FROM nc_org_node c WHERE c.parent_id = n.id);

-- 7.2 非叶子组织（禁止挂大盘）
-- SELECT n.id, n.name, n.parent_id
-- FROM nc_org_node n
-- WHERE EXISTS (SELECT 1 FROM nc_org_node c WHERE c.parent_id = n.id);

-- 7.3 有资源的叶子（链路 9：禁止新增子节点）——必须读投影，不要直接读源表
-- SELECT org_id, resource_kind, resource_id, name, card_count
-- FROM nc_org_resource
-- WHERE org_id IN (
--     SELECT n.id FROM nc_org_node n
--     WHERE NOT EXISTS (SELECT 1 FROM nc_org_node c WHERE c.parent_id = n.id)
-- )
-- ORDER BY org_id, resource_kind, resource_id;

-- 7.4 投影与源表一致性核对（结果应全为 0；非 0 表示存在缺陷，禁止当作“无资源”）
-- SELECT COUNT(*) AS missing_dashboard_projection
-- FROM nc_dashboard d LEFT JOIN nc_org_resource r
--     ON r.org_id = d.org_id AND r.resource_kind = 'DASHBOARD' AND r.resource_id = d.id
-- WHERE r.resource_id IS NULL
--    OR r.name <> d.name
--    OR r.card_count <> (SELECT COUNT(*) FROM nc_card c WHERE c.dashboard_id = d.id);
-- SELECT COUNT(*) AS missing_alert_rule_projection
-- FROM nc_alert_rule a LEFT JOIN nc_org_resource r
--     ON r.org_id = a.org_id AND r.resource_kind = 'ALERT_RULE' AND r.resource_id = a.id
-- WHERE a.org_id IS NOT NULL AND r.resource_id IS NULL;

-- ============================================================================
-- 8. 初始化后自检（人工执行）
-- ============================================================================
-- SELECT * FROM nc_platform_profile;
-- SELECT * FROM nc_channel_config;
-- SELECT COUNT(*) AS org_count FROM nc_org_node;        -- 期望 0
-- SELECT COUNT(*) AS account_count FROM nc_account;     -- 期望 1（超管）
-- SELECT username, role, status, must_change_password FROM nc_account;
