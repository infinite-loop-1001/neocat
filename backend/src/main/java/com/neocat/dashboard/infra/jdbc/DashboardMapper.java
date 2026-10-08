package com.neocat.dashboard.infra.jdbc;

import java.math.BigDecimal;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * 大盘与卡片 Mapper（表 {@code nc_dashboard} / {@code nc_card}）。
 *
 * <p>表定义不使用外键；删除大盘时必须在同一事务内显式级联
 * 删除阈值线、卡片，与 PRD 01 §5.3「删除叶子原子级联删除大盘、卡片与组织告警」一致。
 */
@Mapper
public interface DashboardMapper {
    long countCards(@Param("dashboardId") long dashboardId);

    List<ThresholdLineRow> selectThresholdLines(@Param("cardId") long cardId);

    void deleteThresholdLines(@Param("cardId") long cardId);

    void insertThresholdLine(@Param("cardId") long cardId,
                             @Param("direction") String direction,
                             @Param("value") BigDecimal value);

    @Getter
    @EqualsAndHashCode
    @ToString
    class ThresholdLineRow {
        private final String direction;

        private final BigDecimal value;

        public ThresholdLineRow(String direction, BigDecimal value) {
            this.direction = direction;
            this.value = value;
        }
    }

    DashboardRepositoryAdapter.DashboardRow selectDashboard(@Param("id") long id);

    List<DashboardRepositoryAdapter.DashboardRow> selectDashboardsByOrg(@Param("orgId") long orgId);

    int insertDashboard(DashboardRepositoryAdapter.DashboardRow row);

    int updateDashboard(DashboardRepositoryAdapter.DashboardRow row);

    void deleteDashboard(@Param("id") long id);

    DashboardRepositoryAdapter.CardRow selectCard(@Param("id") long id);

    List<DashboardRepositoryAdapter.CardRow> selectCardsByDashboard(@Param("dashboardId") long dashboardId);

    int insertCard(DashboardRepositoryAdapter.CardRow row);

    int updateCard(DashboardRepositoryAdapter.CardRow row);

    void deleteCard(@Param("id") long id);
}
