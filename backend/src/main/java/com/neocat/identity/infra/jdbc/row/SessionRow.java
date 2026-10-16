package com.neocat.identity.infra.jdbc.row;

import lombok.Data;

import java.sql.Timestamp;

@Data
public class SessionRow {

    private String id;

    private String userAccount;

    private Timestamp expiresAt;

}
