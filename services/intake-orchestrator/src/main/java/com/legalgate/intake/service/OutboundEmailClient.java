package com.legalgate.intake.service;

import com.legalgate.intake.model.NotificationOutboxItem;

/** One outbound mail provider. Exactly one implementation is wired, by legalgate.intake.email-provider. */
interface OutboundEmailClient {

    /**
     * @param threadAnchor the Consultation's original inbound Message-ID, or null to send unthreaded.
     * @return the provider's own message id, or null if it returned none.
     */
    String send(NotificationOutboxItem notification, String threadAnchor);

    boolean isEnabled();
}
