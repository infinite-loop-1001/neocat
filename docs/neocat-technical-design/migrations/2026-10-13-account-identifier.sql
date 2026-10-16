-- 账号标识由数值主键切换为业务标识（登录名），人工执行一次后再部署后端。
-- 执行顺序不可颠倒：先按旧列名回填引用表，再重命名 nc_account 的标识列。
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，本脚本不幂等，重复执行会报错即中止，
-- 因此失败后不要跳步重跑，先确认已完成的语句再继续。
--
-- 影响：nc_account、nc_session、nc_access_history、nc_org_member、
--       nc_effective_leaf、nc_alert_recipient。不新增/删除数据行。

-- 1. nc_session
ALTER TABLE nc_session ADD COLUMN user_account VARCHAR(64) NULL AFTER id;
UPDATE nc_session s JOIN nc_account a ON a.id = s.account_id SET s.user_account = a.user_account;
ALTER TABLE nc_session MODIFY COLUMN user_account VARCHAR(64) NOT NULL COMMENT '账号标识（登录名）';
ALTER TABLE nc_session DROP INDEX idx_nc_session_account, ADD INDEX idx_nc_session_user_account (user_account);
ALTER TABLE nc_session DROP COLUMN account_id;

-- 2. nc_access_history
ALTER TABLE nc_access_history ADD COLUMN user_account VARCHAR(64) NULL AFTER id;
UPDATE nc_access_history h JOIN nc_account a ON a.id = h.account_id SET h.user_account = a.user_account;
ALTER TABLE nc_access_history MODIFY COLUMN user_account VARCHAR(64) NOT NULL COMMENT '账号标识（登录名）';
ALTER TABLE nc_access_history
    DROP INDEX uk_nc_access_history_account_service,
    DROP INDEX idx_nc_access_history_time,
    ADD UNIQUE KEY uk_nc_access_history_account_service (user_account, service_name),
    ADD KEY idx_nc_access_history_time (user_account, accessed_at DESC);
ALTER TABLE nc_access_history DROP COLUMN account_id;

-- 3. nc_org_member
ALTER TABLE nc_org_member ADD COLUMN user_account VARCHAR(64) NULL AFTER org_id;
UPDATE nc_org_member m JOIN nc_account a ON a.id = m.account_id SET m.user_account = a.user_account;
ALTER TABLE nc_org_member MODIFY COLUMN user_account VARCHAR(64) NOT NULL COMMENT '账号标识（登录名）';
ALTER TABLE nc_org_member
    DROP INDEX uk_nc_org_member_org_account,
    DROP INDEX idx_nc_org_member_account,
    ADD UNIQUE KEY uk_nc_org_member_org_account (org_id, user_account),
    ADD KEY idx_nc_org_member_account (user_account);
ALTER TABLE nc_org_member DROP COLUMN account_id;

-- 4. nc_effective_leaf
ALTER TABLE nc_effective_leaf ADD COLUMN user_account VARCHAR(64) NULL AFTER id;
UPDATE nc_effective_leaf l JOIN nc_account a ON a.id = l.account_id SET l.user_account = a.user_account;
ALTER TABLE nc_effective_leaf MODIFY COLUMN user_account VARCHAR(64) NOT NULL COMMENT '账号标识（登录名）';
ALTER TABLE nc_effective_leaf
    DROP INDEX uk_nc_effective_leaf_account_org,
    ADD UNIQUE KEY uk_nc_effective_leaf_account_org (user_account, org_id);
ALTER TABLE nc_effective_leaf DROP COLUMN account_id;

-- 5. nc_alert_recipient
ALTER TABLE nc_alert_recipient ADD COLUMN user_account VARCHAR(64) NULL AFTER rule_id;
UPDATE nc_alert_recipient r JOIN nc_account a ON a.id = r.account_id SET r.user_account = a.user_account;
ALTER TABLE nc_alert_recipient MODIFY COLUMN user_account VARCHAR(64) NOT NULL COMMENT '账号标识（登录名）';
ALTER TABLE nc_alert_recipient
    DROP INDEX uk_nc_alert_recipient_rule_account_channel,
    DROP INDEX idx_nc_alert_recipient_account,
    ADD UNIQUE KEY uk_nc_alert_recipient_rule_account_channel (rule_id, user_account, channel),
    ADD KEY idx_nc_alert_recipient_account (user_account);
ALTER TABLE nc_alert_recipient DROP COLUMN account_id;

-- 6. nc_account：保留自增 id 作为行主键，业务标识列改名为 user_account
ALTER TABLE nc_account
    DROP INDEX uk_nc_account_username,
    CHANGE COLUMN username user_account VARCHAR(64) NOT NULL COMMENT '账号标识（登录名，形如 keith.ji），全局唯一，一期不可修改',
    ADD UNIQUE KEY uk_nc_account_user_account (user_account);

-- 7. 自检：以下查询必须全为 0
-- SELECT COUNT(*) FROM nc_session WHERE user_account NOT IN (SELECT user_account FROM nc_account);
-- SELECT COUNT(*) FROM nc_org_member WHERE user_account NOT IN (SELECT user_account FROM nc_account);
-- SELECT COUNT(*) FROM nc_effective_leaf WHERE user_account NOT IN (SELECT user_account FROM nc_account);
-- SELECT COUNT(*) FROM nc_alert_recipient WHERE user_account NOT IN (SELECT user_account FROM nc_account);
-- SELECT COUNT(*) FROM nc_access_history WHERE user_account NOT IN (SELECT user_account FROM nc_account);
