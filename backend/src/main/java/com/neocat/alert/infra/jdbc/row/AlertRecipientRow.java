package com.neocat.alert.infra.jdbc.row;

import lombok.Data;

/**
 * 收件人行。
 */
@Data
public class AlertRecipientRow {

    private String userAccount;

    private String channel;

}
