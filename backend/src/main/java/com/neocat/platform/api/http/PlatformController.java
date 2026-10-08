package com.neocat.platform.api.http;

import com.neocat.common.time.clock.TimeProvider;

import com.neocat.common.error.ErrorCode;
import com.neocat.common.error.exception.BusinessRuleException;
import com.neocat.platform.api.http.dto.PlatformDtos.*;
import com.neocat.platform.api.http.convert.PlatformConvert;
import com.neocat.platform.domain.profile.PlatformService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 平台 HTTP 入口：时区初始化后不可修改，JSON 契约保持不变。 */
@RestController
@RequestMapping("/api/platform")
public class PlatformController {
    private final PlatformService platform;

    public PlatformController(PlatformService platform) {
        this.platform = platform;
    }

    @GetMapping("/init-status")
    public ResponseEntity<InitStatus> initStatus() {
        return ResponseEntity.ok(new InitStatus(platform.initialized()));
    }

    @PostMapping("/initialize")
    public ResponseEntity<ProfileResponse> initialize(@RequestBody InitRequest body) {
        platform.initialize(PlatformConvert.init(body), TimeProvider.now());
        return profile();
    }

    @GetMapping
    public ResponseEntity<ProfileResponse> profile() {
        return ResponseEntity.ok(PlatformConvert.profile(platform.profile(), platform.channels()));
    }

    @PutMapping("/slow-thresholds")
    public ResponseEntity<ProfileResponse> updateSlowThresholds(@RequestBody SlowThresholdsRequest body) {
        platform.updateSlowThresholds(PlatformConvert.thresholds(body));
        return profile();
    }

    @PutMapping("/channels")
    public ResponseEntity<ProfileResponse> updateChannels(@RequestBody ChannelsRequest body) {
        platform.updateChannels(PlatformConvert.channels(body));
        return profile();
    }

    @PutMapping("/timezone")
    public ResponseEntity<ProfileResponse> changeTimezone() {
        throw new BusinessRuleException(ErrorCode.TIMEZONE_IMMUTABLE);
    }
}
