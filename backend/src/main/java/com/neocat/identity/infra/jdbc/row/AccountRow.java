package com.neocat.identity.infra.jdbc.row;

import lombok.Data;

import java.time.Instant;

/**
 * 数据库行映射（字段名与 nc_account 列一致）。
 */
@Data
public class AccountRow {
    private Long id;

    private String username;

    private String passwordHash;

    private String role;

    private String status;

    private boolean mustChangePassword;

    private Instant createdAt;

    private Instant updatedAt;
}
