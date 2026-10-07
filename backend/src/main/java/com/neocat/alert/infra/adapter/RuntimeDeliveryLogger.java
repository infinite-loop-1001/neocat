package com.neocat.alert.infra.adapter;

import com.neocat.alert.domain.rule.AlertChannel;
import com.neocat.alert.domain.engine.DeliveryLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RuntimeDeliveryLogger implements DeliveryLogger {
    private static final Logger LOG = LoggerFactory.getLogger(RuntimeDeliveryLogger.class);

    @Override
    public void failed(long ruleId, AlertChannel channel, Throwable cause, String message) {
        LOG.warn("Rule {} could not be delivered through {}: {}", ruleId, channel, message, cause);
    }
}
