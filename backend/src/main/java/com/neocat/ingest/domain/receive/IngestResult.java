package com.neocat.ingest.domain.receive;

/**
 * 上报接收结果（技术方案 04 §5）。
 */
@org.springframework.modulith.NamedInterface("tree")
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
public class IngestResult {
    private final IngestStatus status;

    private final String code;

    private final int acceptedTrees;

    private final int duplicateTrees;

    private final int droppedTrees;

    private final int rejectedTrees;

    public IngestResult(IngestStatus status, String code, int acceptedTrees, int duplicateTrees, int droppedTrees, int rejectedTrees) {
        this.status = status;
        this.code = code;
        this.acceptedTrees = acceptedTrees;
        this.duplicateTrees = duplicateTrees;
        this.droppedTrees = droppedTrees;
        this.rejectedTrees = rejectedTrees;
    }

    public static IngestResult accepted(int trees) {
        return new IngestResult(IngestStatus.ACCEPTED, "OK", trees, 0, 0, 0);
    }
    public static IngestResult duplicate(int trees) {
        return new IngestResult(IngestStatus.DUPLICATE, "DUPLICATE", 0, trees, 0, 0);
    }
    public static IngestResult dropped(int trees) {
        return new IngestResult(IngestStatus.DROPPED, "QUEUE_FULL", 0, 0, trees, 0);
    }
    public static IngestResult rejected(String code, int trees) {
        return new IngestResult(IngestStatus.REJECTED, code, 0, 0, 0, trees);
    }
    public int totalTrees() {
        return acceptedTrees + duplicateTrees + droppedTrees + rejectedTrees;
    }
}



