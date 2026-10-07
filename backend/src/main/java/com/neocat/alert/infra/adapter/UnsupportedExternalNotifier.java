package com.neocat.alert.infra.adapter;

import com.neocat.alert.domain.engine.AlertNotification;
import com.neocat.alert.domain.engine.Notifier;
import org.springframework.stereotype.Component;

/** External delivery intentionally deferred; never count an undelivered alert as successful. */
@Component
public class UnsupportedExternalNotifier implements Notifier {
    @Override
    public void send(AlertNotification notification) {
        throw new UnsupportedOperationException("External alert delivery not configured: " + notification.getChannel());
    }
}
