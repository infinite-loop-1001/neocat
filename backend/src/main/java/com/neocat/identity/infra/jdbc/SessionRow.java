package com.neocat.identity.infra.jdbc;

import lombok.Data;

import java.sql.Timestamp;

@Data
public class SessionRow {

    private String id;

    private long accountId;

    private Timestamp expiresAt;

}
