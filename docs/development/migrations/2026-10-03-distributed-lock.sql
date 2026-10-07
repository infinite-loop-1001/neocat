-- 已有库增量迁移；选中 NeoCat 业务库后执行。无外键，不删除业务数据。
-- 现场状态 NOT_RUN。升级应用前先执行；回退旧应用时无需删除此表。
CREATE TABLE IF NOT EXISTS nc_distributed_lock
(
    lock_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    PRIMARY KEY (lock_key)
) ENGINE = InnoDB;

-- MySQL FOR UPDATE 锁住已存在主键行，事务提交/回滚释放。
-- 先 SHOW CREATE TABLE nc_distributed_lock，确认 InnoDB、主键与二进制排序规则。
