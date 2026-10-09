package com.neocat.identity.infra.jdbc;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import com.neocat.identity.infra.jdbc.row.AccountRow;

/**
 * 账号表 Mapper（技术方案 05-mysql-schema.sql 的表 {@code nc_account}）。
 *
 * <p>SQL 集中在 {@code resources/mapper/identity/AccountMapper.xml}，
 * 便于 DBA 审阅与按需优化索引。
 */
@Mapper
public interface AccountMapper {

    AccountRow selectById(@Param("id") long id);

    AccountRow selectByUsername(@Param("username") String username);

    List<AccountRow> selectAll();

    /** 插入并回填自增主键。 */
    int insert(AccountRow row);

    int update(AccountRow row);
}
