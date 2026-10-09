package com.neocat.query.api.http.dto;

import lombok.Getter;
import lombok.Setter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ProblemCategoryRow", description = "Problem 五类汇总行")
@Getter
@Setter
public class ProblemCategory {
    @Schema(description = "分类：EXCEPTION | SLOW_URL | SLOW_SQL | SLOW_CALL | SLOW_CACHE")
    private String category;

    @Schema(description = "该分类的记录数")
    private long total;

    @Schema(description = "该分类是否支持分位统计；异常不支持")
    private boolean supportsPercentile;
}
