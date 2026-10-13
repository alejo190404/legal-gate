package com.legalgate.intake.classifier;

import java.time.Duration;
import java.util.List;

public interface ConsultationClassifierClient {

    // 5m, 10m, 15m: half an hour of retrying an outage before the work is handed to a human.
    // Someone waiting on a reply is the thing being spent here, not compute.
    List<Duration> RETRY_BACKOFF = List.of(Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(15));

    ConsultationClassifierResponse classify(ConsultationClassifierRequest request);

    ConsultationDiagnosticsResponse diagnose(ConsultationDiagnosticsRequest request);

    /** Status Inquiry or needs a lawyer. Throws {@link ClassifierUnavailableException} so the caller can retry. */
    ClientMailIntentResponse classifyClientMail(ClientMailIntentRequest request);
}
