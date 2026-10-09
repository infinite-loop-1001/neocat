package com.neocat.alert.infra.jdbc;

import lombok.Data;

/**
 * 收件人行。
 */
@Data
public class AlertRecipientRow {

    private long accountId;

    private String channel;

}
