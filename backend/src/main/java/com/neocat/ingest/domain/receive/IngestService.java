package com.neocat.ingest.domain.receive;

import com.neocat.common.time.clock.TimeProvider;

import com.neocat.ingest.domain.idempotency.IdempotencyDecision;
import com.neocat.ingest.domain.idempotency.IdempotencyService;
import com.neocat.ingest.domain.tree.MessageTree;
import com.neocat.ingest.domain.validation.FingerprintCalculator;
import com.neocat.ingest.domain.validation.LatenessPolicy;
import com.neocat.ingest.domain.validation.TreeValidator;
import com.neocat.ingest.domain.validation.ValidationOutcome;
import com.neocat.common.queue.BoundedDropQueue;
import com.neocat.common.error.ErrorCode;

import java.time.Instant;
import java.time.ZoneId;
import java.util.function.Supplier;
import java.util.Objects;

import org.apache.commons.collections4.CollectionUtils;
import org.springframework.context.annotation.DependsOn;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;

/**
 * 上报接收编排（PRD 02 §4、§5、§8；技术方案 04 §5.2）。
 *
 * <p>严格处理顺序：
 * <ol>
 *   <li>批次校验（协议版本、批次规模）；</li>
 *   <li>逐棵：单树校验 → 非法整棵拒绝并记录 MALFORMED；</li>
 *   <li>逐棵：事件时间判定 → 过期整棵拒绝并记录 EXPIRED（不进入任何报表）；</li>
 *   <li>逐棵：<b>身份自动发现（先于入队）</b> → 队列满也能发现服务/实例；</li>
 *   <li>逐棵：幂等判定 → DUPLICATE / CONFLICT；</li>
 *   <li>逐棵：入队 offer → 成功 ACCEPTED；满则 DROPPED 并记录 QUEUE_FULL；</li>
 *   <li>立即返回，不等待分析。</li>
 * </ol>
 *
 * <p>不变量：步骤 4 必须早于步骤 6。这是 PRD 02 §5「即使之后队列已满导致整棵树丢弃，
 * 服务和实例仍然可以在目录中被发现」的唯一实现方式。
 */
@NamedInterface("tree")
@Service
@DependsOn("ingestConfig")
public class IngestService {

    private static final String CODE_TREE_EXPIRED = ErrorCode.TREE_EXPIRED.name();

    private static final String CODE_ID_CONFLICT = ErrorCode.ID_CONFLICT.name();

    private final TreeValidator validator;

    private final LatenessPolicy lateness;

    private final IdempotencyService idempotency;

    private final FingerprintCalculator fingerprints;

    private final CatalogGateway catalog;

    private final BoundedDropQueue<MessageTree> queue;

    private final QualityEventSink quality;

    private final Supplier<ZoneId> zone;

    public IngestService(TreeValidator validator,
                         LatenessPolicy lateness,
                         IdempotencyService idempotency,
                         FingerprintCalculator fingerprints,
                         CatalogGateway catalog,
                         BoundedDropQueue<MessageTree> queue,
                         QualityEventSink quality,
                         Supplier<ZoneId> zone) {
        this.validator = validator;
        this.lateness = lateness;
        this.idempotency = idempotency;
        this.fingerprints = fingerprints;
        this.catalog = catalog;
        this.queue = queue;
        this.quality = quality;
        this.zone = zone;
    }

    /**
     * 接收一个上报批次。
     *
     * <p>批次状态取「最高优先级结果」：只要有树被接收即 ACCEPTED；
     * 否则按 REJECTED → DROPPED → DUPLICATE 顺序取第一个出现过的状态，
     * 便于客户端区分「需要修正」与「无需重试」。
     */
    public IngestResult accept(IngestBatch batch, int payloadBytes) {
        ValidationOutcome batchCheck = validator.validateBatch(batch, payloadBytes);
        if (!batchCheck.isValid()) {
            return IngestResult.rejected(batchCheck.getCode(), Objects.isNull(batch) || CollectionUtils.isEmpty(batch.getTrees())
                    ? 0 : batch.getTrees().size());
        }

        Instant now = TimeProvider.now();
        ZoneId zoneId = zone.get();
        int accepted = 0;
        int duplicate = 0;
        int dropped = 0;
        int rejected = 0;
        boolean sawRejection = false;
        boolean sawDrop = false;
        String rejectionCode = null;

        for (MessageTree tree : batch.getTrees()) {
            ValidationOutcome treeCheck = validator.validateTree(tree);
            if (!treeCheck.isValid()) {
                rejected++;
                sawRejection = true;
                rejectionCode = treeCheck.getCode();
                quality.record(QualityType.MALFORMED,
                        Objects.isNull(tree) ? null : tree.getServiceName(),
                        Objects.isNull(tree) ? null : tree.getMessageId(),
                        treeCheck.getMessage(), now);
                continue;
            }

            if (!lateness.acceptable(tree.getTreeTimestamp(), now, zoneId)) {
                rejected++;
                sawRejection = true;
                rejectionCode = CODE_TREE_EXPIRED;
                quality.record(QualityType.EXPIRED, tree.getServiceName(),
                        tree.getMessageId(), "事件时间超出可接收窗口", now);
                continue;
            }

            // 关键：发现先于入队
            catalog.discover(tree.getServiceName(), tree.getInstanceId(), now);

            IdempotencyDecision decision = idempotency.decide(tree.getMessageId(), fingerprints.fingerprint(tree));
            if (Objects.equals(decision, IdempotencyDecision.DUPLICATE)) {
                duplicate++;
                continue;
            }
            if (Objects.equals(decision, IdempotencyDecision.CONFLICT)) {
                rejected++;
                sawRejection = true;
                rejectionCode = CODE_ID_CONFLICT;
                quality.record(QualityType.ID_CONFLICT, tree.getServiceName(),
                        tree.getMessageId(), "同一 messageId 携带了不同内容", now);
                continue;
            }

            if (queue.offer(tree)) {
                accepted++;
            } else {
                dropped++;
                sawDrop = true;
                quality.record(QualityType.QUEUE_FULL, tree.getServiceName(),
                        tree.getMessageId(), "队列已满，丢弃监控数据且不补算", now);
            }
        }

        if (accepted > 0) {
            return new IngestResult(IngestStatus.ACCEPTED, "OK", accepted, duplicate, dropped, rejected);
        }
        if (sawRejection) {
            return new IngestResult(IngestStatus.REJECTED,
                    Objects.isNull(rejectionCode) ? ErrorCode.MALFORMED_TREE.name() : rejectionCode,
                    accepted, duplicate, dropped, rejected);
        }
        if (sawDrop) {
            return new IngestResult(IngestStatus.DROPPED, "QUEUE_FULL", accepted, duplicate, dropped, rejected);
        }
        return IngestResult.duplicate(duplicate);
    }

    public QueueStats queueStats() {
        return new QueueStats(queue.size(), queue.capacity(), queue.droppedCount(), queue.watermark());
    }
}