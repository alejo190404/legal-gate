package com.legalgate.intake.classifier;

public interface ConsultationClassifierClient {
    ConsultationClassifierResponse classify(ConsultationClassifierRequest request);

    ConsultationDiagnosticsResponse diagnose(ConsultationDiagnosticsRequest request);

    /** Status Inquiry or needs a lawyer. Throws {@link ClassifierUnavailableException} so the caller can retry. */
    ClientMailIntentResponse classifyClientMail(ClientMailIntentRequest request);
}
