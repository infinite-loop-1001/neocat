package com.neocat.ingest.domain.validation;

import com.neocat.ingest.domain.receive.IngestBatch;
import com.neocat.ingest.domain.tree.MessageTree;
import com.neocat.ingest.domain.tree.MetricValue;
import com.neocat.ingest.domain.tree.NodeKind;
import com.neocat.ingest.domain.tree.RawNode;
import com.neocat.common.config.IngestConfig;
import java.util.HashSet;
import java.util.Set;
import java.util.Objects;
import org.apache.commons.collections4.CollectionUtils;

/**
 * 上报校验链（技术方案 04 §4）。
 *
 * <p>校验顺序：协议版本 → 批次规模 → 单树必填字段 → 节点数量 → 节点内唯一性与数值合法性。
 * 任一棵树校验失败则**整棵拒绝**，不出现「只进部分分析域」的结果（PRD 02 §11）。
 *
 * <p>注意：事件时间（迟到）校验不在此处，它由接收链路在入队前单独判定，
 * 因为过期需要「写质量事件 + 整棵拒绝」两个动作（见 {@code IngestService}）。
 */
@org.springframework.modulith.NamedInterface("tree")
@org.springframework.stereotype.Component
public class TreeValidator {

    private static final int MAX_FIELD_LENGTH = 256;

    /** 批次级校验：协议版本、批次树数、批次字节。 */
    public ValidationOutcome validateBatch(IngestBatch batch, int payloadBytes) {
        if (Objects.isNull(batch) || Objects.isNull(batch.getProtocolVersion())
                || !Objects.equals(IngestBatch.SUPPORTED_VERSION, batch.getProtocolVersion())) {
            return ValidationOutcome.reject(ValidationOutcome.UNSUPPORTED_VERSION,
                    "不支持的协议版本：" + (Objects.isNull(batch) ? null : batch.getProtocolVersion()));
        }
        if (CollectionUtils.isEmpty(batch.getTrees())) {
            return ValidationOutcome.reject(ValidationOutcome.BATCH_TOO_LARGE, "批次不能为空");
        }
        if (batch.getTrees().size() > IngestConfig.MAX_TREES_PER_BATCH) {
            return ValidationOutcome.reject(ValidationOutcome.BATCH_TOO_LARGE,
                    "批次树数超过上限 " + IngestConfig.MAX_TREES_PER_BATCH);
        }
        if (payloadBytes > IngestConfig.MAX_BATCH_BYTES) {
            return ValidationOutcome.reject(ValidationOutcome.BATCH_TOO_LARGE,
                    "批次字节超过上限 " + IngestConfig.MAX_BATCH_BYTES);
        }
        return ValidationOutcome.ok();
    }
    /** 单树校验：必填字段、字段长度、节点数量、节点唯一性、数值合法性。 */
    public ValidationOutcome validateTree(MessageTree tree) {
        if (Objects.isNull(tree)) {
            return ValidationOutcome.reject(ValidationOutcome.MALFORMED_TREE, "MessageTree 为空");
        }
        ValidationOutcome required = validateRequiredFields(tree);
        if (!required.isValid()) {
            return required;
        }
        if (CollectionUtils.isEmpty(tree.getNodes())) {
            return ValidationOutcome.reject(ValidationOutcome.MALFORMED_TREE, "树内节点不能为空");
        }
        if (tree.getNodes().size() > IngestConfig.MAX_NODES_PER_TREE) {
            return ValidationOutcome.reject(ValidationOutcome.TREE_TOO_LARGE,
                    "节点数超过上限 " + IngestConfig.MAX_NODES_PER_TREE);
        }
        return validateNodes(tree);
    }

    // ── 内部 ─────────────────────────────────────────────────

    private ValidationOutcome validateRequiredFields(MessageTree tree) {
        if (isBlankOrTooLong(tree.getServiceName())) {
            return ValidationOutcome.reject(ValidationOutcome.MALFORMED_TREE, "serviceName 非法");
        }
        if (isBlankOrTooLong(tree.getInstanceId())) {
            return ValidationOutcome.reject(ValidationOutcome.MALFORMED_TREE, "instanceId 非法");
        }
        if (isBlankOrTooLong(tree.getMessageId())) {
            return ValidationOutcome.reject(ValidationOutcome.MALFORMED_TREE, "messageId 非法");
        }
        if (Objects.nonNull(tree.getRootMessageId()) && tree.getRootMessageId().length() > MAX_FIELD_LENGTH) {
            return ValidationOutcome.reject(ValidationOutcome.MALFORMED_TREE, "rootMessageId 过长");
        }
        if (Objects.nonNull(tree.getParentMessageId()) && tree.getParentMessageId().length() > MAX_FIELD_LENGTH) {
            return ValidationOutcome.reject(ValidationOutcome.MALFORMED_TREE, "parentMessageId 过长");
        }
        return ValidationOutcome.ok();
    }
    private ValidationOutcome validateNodes(MessageTree tree) {
        Set<String> seenNodeIds = new HashSet<>();
        for (RawNode node : tree.getNodes()) {
            if (Objects.isNull(node)) {
                return ValidationOutcome.reject(ValidationOutcome.MALFORMED_TREE, "节点为空");
            }
            if (isBlankOrTooLong(node.getNodeId())) {
                return ValidationOutcome.reject(ValidationOutcome.MALFORMED_TREE, "nodeId 非法");
            }
            if (!seenNodeIds.add(node.getNodeId())) {
                return ValidationOutcome.reject(ValidationOutcome.MALFORMED_TREE,
                        "nodeId 在树内重复：" + node.getNodeId());
            }
            if (Objects.isNull(node.getKind())) {
                return ValidationOutcome.reject(ValidationOutcome.MALFORMED_TREE, "节点类型缺失");
            }
            if (node.getDurationMs() < 0) {
                return ValidationOutcome.reject(ValidationOutcome.MALFORMED_TREE, "durationMs 不能为负");
            }
            if (node.getKind() == NodeKind.METRIC) {
                ValidationOutcome metric = validateMetric(node);
                if (!metric.isValid()) {
                    return metric;
                }
            }
        }
        return ValidationOutcome.ok();
    }
    private ValidationOutcome validateMetric(RawNode node) {
        MetricValue metric = node.getMetric();
        if (Objects.isNull(metric)) {
            return ValidationOutcome.reject(ValidationOutcome.MALFORMED_TREE, "Metric 节点缺少数值");
        }
        if (Double.isNaN(metric.getValue()) || Double.isInfinite(metric.getValue())) {
            return ValidationOutcome.reject(ValidationOutcome.MALFORMED_TREE,
                    "Metric 数值非法：" + metric.getValue());
        }
        return ValidationOutcome.ok();
    }
    private boolean isBlankOrTooLong(String value) {
        return Objects.isNull(value) || value.isBlank() || value.length() > MAX_FIELD_LENGTH;
    }
}
