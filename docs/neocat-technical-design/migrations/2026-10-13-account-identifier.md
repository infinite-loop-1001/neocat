# 账号标识改为业务标识（user_account）部署说明

1. **先人工执行** `2026-10-13-account-identifier.sql`，再部署后端。脚本不清空数据、不自动执行，
   也不幂等：MySQL 8 没有 `ADD COLUMN IF NOT EXISTS`，重复执行会在第二条语句上失败中止。
2. 全新环境直接用更新后的 `../05-mysql-schema.sql`，不要执行本脚本。

## 变更内容

- `nc_account.username` → `nc_account.user_account`：账号的业务标识就是登录名（形如 `keith.ji`）。
  自增 `id` 保留为行主键，只服务于存储层，不再被业务代码引用。
- 引用账号的列由数值 `account_id` 改为 `user_account VARCHAR(64)`：
  `nc_session`、`nc_access_history`、`nc_org_member`、`nc_effective_leaf`、`nc_alert_recipient`。
  唯一键与索引随之改为基于 `user_account`，`nc_access_history` 的唯一键仍是
  `(user_account, service_name)`，`nc_alert_recipient` 仍是 `(rule_id, user_account, channel)`。
- 对外的账号标识字段与路径参数统一叫 `userAccount`：`/api/users/{userAccount}/...`、
  `DELETE /api/orgs/{id}/members/{userAccount}`、`MemberDraft.userAccount`、告警 `recipients` 为账号标识数组。
  响应中的账号摘要不再返回数值 `id`。

## 执行要点

- 迁移按表逐一「加列 → 从 `nc_account` 回填 → 收紧为非空 → 换索引 → 删旧列」；
  回填依赖 `nc_account` 当时的 `username` 与自增 `id`，因此必须整脚本顺序执行，不能跳步。
- 回填后 `user_account` 若为 NULL，说明存在指向已删除账号的悬挂引用：先人工确认这些行，
  不要直接收紧为非空。
- 脚本末尾的自检查询必须全部为 0，否则说明回填不完整，应回滚后重跑。
- 一期不支持改名，`user_account` 只在校验通过后才切换；改名会让 `nc_session`、
  `nc_org_member`、`nc_effective_leaf`、`nc_alert_recipient`、`nc_access_history` 的引用一并失效。
