package com.neocat.alert.domain.event;

import com.neocat.alert.domain.entity.vo.source.MinutePoint;
import com.neocat.alert.domain.entity.vo.rule.AlertChannel;
import lombok.*;
import org.springframework.modulith.NamedInterface;

import java.util.List;

/**
 * 告警通知领域事件（PRD 06 §10）。
 *
 * <p>窗口满足时由判定引擎发布，一个事件代表**一次通知投递**：
 * 同一触发分钟点按通道各产生一次。
 *
 * <p>**仅存在于运行期**：一期不保存正式告警触发事件历史（PRD 06 §11），
 * 事件不落库，也不提供站内投递历史查询。
 */
@NamedInterface("alert")
@Getter
@EqualsAndHashCode
@ToString
@AllArgsConstructor
@Builder
public class AlertTriggeredEvent {

    /**
     * 规则 Id
     **/
    private final Long ruleId;

    /**
     *  规则名
     **/
    private final String ruleName;

    /**
     * 有效收件人账号 Id
     */
    private final List<String> recipients;

    /**
     * 目标通道
     */
    private final List<AlertChannel> channels;

    /**
     * 通知内容
     */
    private final String message;

    /**
     * 触发分钟点（epoch millis）
     */
    private final Long triggeredAt;

    /**
     * 触发规则的窗口大小
     */
    private final Integer windowSize;

    /**
     * 触发判定的窗口点值：满足条件的 X 个完整分钟点，**时间升序** {@code [t-X+1, t]}。
     *
     * <p>窗口点数 X 取自规则的 {@code windowPoints}；例如 X=3、触发于 t 时，
     * 这里就是 t-2、t-1、t 三个点，其统计项取值即形如 {@code [1, 2, 3]}。
     * 每个点携带该分钟的分钟点与各统计项取值，缺数点不会出现在其中
     * （任一缺数点都会使窗口不触发）。
     */
    private final List<MinutePoint> triggerPoints;
}
