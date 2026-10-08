package com.neocat.platform.api.http;

import com.neocat.common.time.clock.TimeProvider;

import com.neocat.common.error.ErrorCode;
import com.neocat.common.error.exception.BusinessRuleException;
import com.neocat.platform.api.http.dto.PlatformDtos.*;
import com.neocat.platform.api.http.convert.PlatformConvert;
import com.neocat.platform.domain.profile.PlatformService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 平台 HTTP 入口：时区初始化后不可修改，JSON 契约保持不变。 */
@Tag(name = "平台配置", description = "初始化、平台档案、慢阈值与通知通道")
@RestController
@RequestMapping("/api/platform")
public class PlatformController {
    private final PlatformService platform;

    public PlatformController(PlatformService platform) {
        this.platform = platform;
    }

    @Operation(operationId = "platformInitStatus", summary = "查询平台是否已初始化",
            description = "未初始化时前端进入初始化向导；本端点允许匿名访问。")
    @ApiResponse(responseCode = "200", description = "{ initialized: bool }")
    @SecurityRequirements
    @GetMapping("/init-status")
    public ResponseEntity<InitStatus> initStatus() {
        return ResponseEntity.ok(new InitStatus(platform.initialized()));
    }

    @Operation(operationId = "initializePlatform", summary = "初始化平台",
            description = "未初始化时允许匿名调用；时区一旦初始化即不可修改，重复初始化返回 409。")
    @ApiResponse(responseCode = "200", description = "初始化后的平台档案")
    @ApiResponse(responseCode = "409", description = "已初始化：ALREADY_INITIALIZED")
    @SecurityRequirements
    @PostMapping("/initialize")
    public ResponseEntity<ProfileResponse> initialize(@RequestBody InitRequest body) {
        platform.initialize(PlatformConvert.init(body), TimeProvider.now());
        return profile();
    }

    @Operation(operationId = "getPlatformProfile", summary = "查询平台档案",
            description = "返回平台时区、慢阈值与通道开关；所有时间字段按该时区解释。")
    @ApiResponse(responseCode = "200", description = "平台档案")
    @GetMapping
    public ResponseEntity<ProfileResponse> profile() {
        return ResponseEntity.ok(PlatformConvert.profile(platform.profile(), platform.channels()));
    }

    @Operation(operationId = "updateSlowThresholds", summary = "更新慢阈值",
            description = "只影响后续分析，不回算历史；阈值非法时返回 400。")
    @ApiResponse(responseCode = "200", description = "更新后的平台档案")
    @ApiResponse(responseCode = "400", description = "阈值非法：INVALID_PARAM")
    @PutMapping("/slow-thresholds")
    public ResponseEntity<ProfileResponse> updateSlowThresholds(@RequestBody SlowThresholdsRequest body) {
        platform.updateSlowThresholds(PlatformConvert.thresholds(body));
        return profile();
    }

    @Operation(operationId = "updateChannels", summary = "更新通知通道开关与凭据",
            description = "未配置的通道不出现在告警可选通道中。")
    @ApiResponse(responseCode = "200", description = "更新后的平台档案")
    @PutMapping("/channels")
    public ResponseEntity<ProfileResponse> updateChannels(@RequestBody ChannelsRequest body) {
        platform.updateChannels(PlatformConvert.channels(body));
        return profile();
    }

    @Operation(operationId = "changeTimezone", summary = "修改平台时区（一期不支持）",
            description = "时区初始化后不可修改；本端点恒定返回 422 TIMEZONE_IMMUTABLE。")
    @ApiResponse(responseCode = "422", description = "时区不可修改：TIMEZONE_IMMUTABLE")
    @PutMapping("/timezone")
    public ResponseEntity<ProfileResponse> changeTimezone() {
        throw new BusinessRuleException(ErrorCode.TIMEZONE_IMMUTABLE);
    }
}
