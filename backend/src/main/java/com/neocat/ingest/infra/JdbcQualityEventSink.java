package com.neocat.ingest.infra;

import com.neocat.ingest.domain.receive.QualityEventSink;
import com.neocat.ingest.domain.receive.QualityType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;

/** Writes quality events to the same ClickHouse table used by the report query. */
@Component
public class JdbcQualityEventSink implements QualityEventSink {
    private final JdbcTemplate jdbc;

    @org.springframework.beans.factory.annotation.Autowired
    public JdbcQualityEventSink(@Qualifier("clickHouseDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    JdbcQualityEventSink(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }
    @Override
    public void record(QualityType type, String serviceName, String messageId, String detail, Instant at) {
        jdbc.update("""
                INSERT INTO neocat.nc_quality_event
                (event_time, event_type, service, message_id, detail, count)
                VALUES (?, ?, ?, ?, ?, 1)
                """, Timestamp.from(at), type.name(), serviceName == null ? "" : serviceName,
                messageId == null ? "" : messageId, detail == null ? "" : detail);
    }
}
