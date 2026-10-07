package com.neocat.query.infra.datasource

import spock.lang.Specification
import com.neocat.analysis.domain.analyzer.*
import com.neocat.analysis.domain.bucket.*
import com.neocat.analysis.domain.dependency.*
import com.neocat.analysis.domain.metric.*
import com.neocat.analysis.domain.schedule.*
import com.neocat.analysis.infra.jdbc.JdbcReportBucketSink
import java.time.*
import java.sql.*
import javax.sql.DataSource
import org.mockito.ArgumentCaptor
import static org.mockito.Mockito.*

class ReportSnapshotJdbcSpec extends Specification {
    static void verifyPrepared(Connection connection, ArgumentCaptor<String> sql) {
        verify(connection).prepareStatement(sql.capture())
    }
    def "历史查询参数化，快照重写先去重，再合并 count 和最后值"() {
        given:
        def ds = mock(DataSource)
        def connection = mock(Connection)
        def statement = mock(PreparedStatement)
        def result = mock(ResultSet)
        when(ds.connection).thenReturn(connection)
        when(connection.prepareStatement(anyString())).thenReturn(statement)
        when(statement.executeQuery()).thenReturn(result)
        when(result.next()).thenReturn(false)
        def query = new JdbcClickHouseReportQuery(ds)
        def from = Instant.parse('2026-10-02T10:00:00Z')
        when:
        query.minuteRows('order', 'METRIC', 'm', "x='unsafe';", 'all', from, from.plusSeconds(60))
        then:
        def sql = ArgumentCaptor.forClass(String)
        verifyPrepared(connection, sql)
        sql.value.contains('argMax(tuple(count,')
        sql.value.contains('snapshot_source')
        sql.value.contains('sum(value_count)')
        sql.value.contains('argMax(value_last, tuple(value_last_time, value_last))')
        !sql.value.contains('unsafe')
        sql.value.contains('AND metric_labels = ?')
        verify(statement).setString(14, "x='unsafe';"); true
        verify(statement).setString(15, 'all'); true
    }

    def "sink 带同一来源递增版本，迁移不存在的列与应用写入对齐"() {
        given:
        def ds = mock(DataSource)
        def connection = mock(Connection)
        def statement = mock(PreparedStatement)
        def database = mock(DatabaseMetaData)
        when(ds.connection).thenReturn(connection)
        when(connection.prepareStatement(anyString())).thenReturn(statement)
        when(connection.metaData).thenReturn(database)
        when(statement.connection).thenReturn(connection)
        when(database.supportsBatchUpdates()).thenReturn(true)
        when(statement.executeBatch()).thenReturn([1] as int[])
        def sink = new JdbcReportBucketSink(ds)
        def time = Instant.parse('2026-10-02T10:00:00Z')
        def row = new AggregatedRow(SeriesKey.metric('order','m',''),time,AggregationLevel.MINUTE,60)
        row.addValue(12d,1)
        row.mergeLastValue(12d,time)
        when:
        sink.writeMinuteBuckets([row])
        sink.writeMinuteBuckets([row])
        then:
        def sources = ArgumentCaptor.forClass(String)
        verify(statement,times(2)).setString(eq(20), sources.capture()); true
        sources.allValues[0] == sources.allValues[1]
        def versions = ArgumentCaptor.forClass(Long)
        verify(statement,times(2)).setObject(eq(21),versions.capture()); true
        versions.allValues == [1L,2L]
        def migration = new File('../docs/neocat-technical-design/migrations/2026-10-02-metric-heartbeat.sql').text
        migration.contains('value_count UInt64')
        migration.contains("value_last_time Nullable(DateTime64(3, 'UTC'))")
        migration.contains('snapshot_source String')
        migration.contains('nc_metric_label_metadata')
    }

    def "JDBC 读行保留合法零及最后事件时间，旧 null 不变为零"() {
        given:
        def ds = mock(DataSource)
        def connection = mock(Connection)
        def statement = mock(PreparedStatement)
        def result = mock(ResultSet)
        when(ds.connection).thenReturn(connection)
        when(connection.prepareStatement(anyString())).thenReturn(statement)
        when(statement.executeQuery()).thenReturn(result)
        when(result.next()).thenReturn(true,false)
        when(result.getString('service')).thenReturn('order')
        when(result.getString('kind')).thenReturn('HEARTBEAT')
        when(result.getString('type')).thenReturn('jvm')
        when(result.getString('name')).thenReturn('gc-count')
        when(result.getString('instance')).thenReturn('one')
        def time = Instant.parse('2026-10-02T10:00:00Z')
        when(result.getTimestamp('bucket_start')).thenReturn(Timestamp.from(time))
        when(result.getLong('total_value_count')).thenReturn(2L)
        when(result.getObject('last_value')).thenReturn(lastValue)
        when(result.getTimestamp('last_sample_time')).thenReturn(lastValue == null ? null : Timestamp.from(time.plusSeconds(30)))
        when:
        def row = new JdbcClickHouseReportQuery(ds).minuteRows('order','HEARTBEAT','jvm','gc-count','one',time,time.plusSeconds(60))[0]
        then:
        row.getValueLast() == lastValue
        row.getValueLastTime() == (lastValue == null ? null : time.plusSeconds(30))
        row.getValueCount() == 2L
        where:
        lastValue << [0d,null]
    }

    def "日桶查询与落库使用平台日期而非 UTC 日期"() {
        given:
        def ds = mock(DataSource)
        def connection = mock(Connection)
        def statement = mock(PreparedStatement)
        def result = mock(ResultSet)
        def database = mock(DatabaseMetaData)
        when(ds.connection).thenReturn(connection)
        when(connection.prepareStatement(anyString())).thenReturn(statement)
        when(statement.executeQuery()).thenReturn(result)
        when(result.next()).thenReturn(false)
        when(connection.metaData).thenReturn(database)
        when(statement.connection).thenReturn(connection)
        when(database.supportsBatchUpdates()).thenReturn(true)
        when(statement.executeBatch()).thenReturn([1] as int[])
        def zone = ZoneId.of('Asia/Shanghai')
        def time = Instant.parse('2026-10-01T16:00:00Z')
        def row = new AggregatedRow(SeriesKey.metric('order','m',''),time,AggregationLevel.DAY,86400)
        row.addValue(10d,2)
        row.mergeLastValue(10d,time)
        when:
        new JdbcReportBucketSink(ds,{zone}).writeDayBuckets([row])
        new JdbcClickHouseReportQuery(ds,{zone}).dayRows('order','METRIC','m',null,'all',time,time.plusSeconds(86400))
        then:
        verify(statement).setObject(8, java.sql.Date.valueOf('2026-10-02')); true
        verify(statement).setObject(3, java.sql.Date.valueOf('2026-10-02')); true
        verify(statement).setObject(4, java.sql.Date.valueOf('2026-10-03')); true
    }

    def "metadata 重试与内存重复快照只保留每来源最大版本及合并标记"() {
        given:
        def ds = mock(DataSource)
        def connection = mock(Connection)
        def statement = mock(PreparedStatement)
        def result = mock(ResultSet)
        when(ds.connection).thenReturn(connection)
        when(connection.prepareStatement(anyString())).thenReturn(statement)
        when(statement.executeQuery()).thenReturn(result)
        when(result.next()).thenReturn(true,false)
        def memory = new com.neocat.analysis.infra.store.InMemoryMetricLabelMetadata()
        def time = Instant.parse('2026-10-02T10:00:00Z')
        memory.record('order','m',[x:'y'],SeriesKey.OTHER_LABELS,time)
        memory.record('order','m',[x:'y'],SeriesKey.OTHER_LABELS,time)
        def entry = memory.entries(time,time.plusSeconds(60))[0]
        when(result.getString('service')).thenReturn('order')
        when(result.getString('metric_name')).thenReturn('m')
        when(result.getString('labels')).thenReturn('x=y;')
        when(result.getString('decoded_labels')).thenReturn('{"x":"y"}')
        when(result.getString('source')).thenReturn(entry.getSource())
        when(result.getTimestamp('hour')).thenReturn(Timestamp.from(time))
        when(result.getLong('report_count')).thenReturn(1L)
        when(result.getBoolean('is_merged')).thenReturn(true)
        when:
        def entries = new JdbcMetricMetadataPort(ds,new com.fasterxml.jackson.databind.ObjectMapper(),memory).entries('order','m',time,time.plusSeconds(60))
        then:
        entries.size() == 1
        entries[0].getVersion() == 2L
        entries[0].isMerged()
        entries[0].getLabels() == [x:'y']
    }
}
