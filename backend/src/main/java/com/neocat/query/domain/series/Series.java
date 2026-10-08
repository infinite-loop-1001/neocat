package com.neocat.query.domain.series;

import com.neocat.query.domain.stat.Stat;

import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 一条序列查询结果（技术方案 03 §4.10）。
 */
@NamedInterface("query")
@Getter
@EqualsAndHashCode
@ToString
public class Series {
    private final String service;

    private final String kind;

    private final String type;

    private final String name;

    private final Stat stat;

    private final long bucketSeconds;

    private final long from;

    private final long to;

    private final List<Point> points;

    public Series(String service, String kind, String type, String name, Stat stat, long bucketSeconds, long from, long to, List<Point> points) {
        this.service = service;
        this.kind = kind;
        this.type = type;
        this.name = name;
        this.stat = stat;
        this.bucketSeconds = bucketSeconds;
        this.from = from;
        this.to = to;
        this.points = points;
    }

    /** 该序列的缺口点位。 */
    public List<Point> gaps() {
        return points.stream().filter(p -> p.getQuality().gap()).toList();
    }
    /** 部分覆盖的点位。 */
    public List<Point> partials() {
        return points.stream().filter(Point::partial).toList();
    }
}