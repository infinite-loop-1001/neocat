package com.neocat.alert.infra.jdbc.row;

import lombok.Data;

import java.sql.Timestamp;

/**
 * 窗口点行。
 */
@Data
public class AlertWindowPointRow {

    private Timestamp pointMinute;

    private boolean satisfied;

}
