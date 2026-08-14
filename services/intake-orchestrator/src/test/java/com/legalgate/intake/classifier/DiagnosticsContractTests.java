package com.legalgate.intake.classifier;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalgate.intake.service.DiagnosticsService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Golden examples pinning the two cross-service contracts. Both sides of each boundary assert on
 * the same literal, so a rename on either side breaks a test instead of silently breaking intake.
 *
 * <ul>
 *   <li>The diagnose payload is asserted as accepted input in
 *       consultation-classifier/tests/test_diagnostics.py.</li>
 *   <li>The token-bearing address is asserted to resolve to this tenant in mail-ingress's
 *       CloudMailinWebhookControllerTests.</li>
 * </ul>
 */
class DiagnosticsContractTests {

    private static final String GOLDEN_REPLY_ADDRESS =
            "firma-demo+d9f2c7a1e4b8d0356af71c2e5d8093b4a@intake.legal-gate.co";
    private static final String GOLDEN_REPLY_TOKEN = "9f2c7a1e4b8d0356af71c2e5d8093b4a";

    private static final String GOLDEN_DIAGNOSE_REQUEST = """
            {
              "diagnosticsPrompt": "Tomamos casos laborales. Necesitamos la fecha del despido y el tipo de contrato.",
              "email": {
                "subject": "Consulta laboral",
                "plain": "Me despidieron.",
                "html": null,
                "sender": "Maria Perez <maria@example.com>",
                "recipients": ["firma-demo@intake.legal-gate.co"],
                "messageId": "<message-123@example.com>"
              },
              "exchange": [
                {"role": "LEGALGATE", "body": "Cual fue la fecha del despido?"},
                {"role": "CLIENT", "body": "El 3 de marzo."}
              ],
              "systemPrompt": "Decide si la firma toma el caso.",
              "promptVersion": "consultation-diagnostics-v1"
            }
            """;

    private static final String GOLDEN_DIAGNOSE_RESPONSE = """
            {
              "verdict": "ask",
              "question": "Que tipo de contrato tenia?",
              "acknowledgment": "el despido en su trabajo",
              "reason": "Falta el tipo de contrato.",
              "summary": "Despido el 3 de marzo."
            }
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesTheDiagnoseRequestExactlyAsTheClassifierExpectsIt() throws Exception {
        ConsultationDiagnosticsRequest request = new ConsultationDiagnosticsRequest(
                "Tomamos casos laborales. Necesitamos la fecha del despido y el tipo de contrato.",
                new ConsultationClassifierRequest.InboundEmail(
                        "Consulta laboral", "Me despidieron.", null, "Maria Perez <maria@example.com>",
                        List.of("firma-demo@intake.legal-gate.co"), "<message-123@example.com>"),
                List.of(
                        new ConsultationDiagnosticsRequest.Message("LEGALGATE", "Cual fue la fecha del despido?"),
                        new ConsultationDiagnosticsRequest.Message("CLIENT", "El 3 de marzo.")),
                "Decide si la firma toma el caso.",
                "consultation-diagnostics-v1");

        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(request)))
                .isEqualTo(objectMapper.readTree(GOLDEN_DIAGNOSE_REQUEST));
    }

    @Test
    void deserializesTheDiagnoseResponseTheClassifierReturns() throws Exception {
        ConsultationDiagnosticsResponse response =
                objectMapper.readValue(GOLDEN_DIAGNOSE_RESPONSE, ConsultationDiagnosticsResponse.class);

        assertThat(response.verdict()).isEqualTo(ConsultationDiagnosticsResponse.ASK);
        assertThat(response.question()).isEqualTo("Que tipo de contrato tenia?");
        assertThat(response.acknowledgment()).isEqualTo("el despido en su trabajo");
        assertThat(response.reason()).isEqualTo("Falta el tipo de contrato.");
        assertThat(response.summary()).isEqualTo("Despido el 3 de marzo.");
    }

    @Test
    void readsTheReplyTokenFromTheAddressMailIngressForwardsUntouched() {
        assertThat(DiagnosticsService.replyTokenFrom(List.of(GOLDEN_REPLY_ADDRESS)))
                .contains(GOLDEN_REPLY_TOKEN);
    }

    @Test
    void ignoresRecipientsThatCarryNoWellFormedToken() {
        assertThat(DiagnosticsService.replyTokenFrom(List.of("firma-demo@intake.legal-gate.co")))
                .isEmpty();
        assertThat(DiagnosticsService.replyTokenFrom(List.of("firma-demo+notatoken@intake.legal-gate.co")))
                .isEmpty();
        assertThat(DiagnosticsService.replyTokenFrom(List.of("firma-demo+dabc@intake.legal-gate.co")))
                .isEmpty();
    }

    @Test
    void generatesUnguessableTokensOfTheSameShapeAsTheGoldenExample() {
        String token = DiagnosticsService.newReplyToken();

        assertThat(token).hasSize(GOLDEN_REPLY_TOKEN.length()).matches("[0-9a-f]+");
        assertThat(DiagnosticsService.newReplyToken()).isNotEqualTo(token);
    }

    @Test
    void matchesTheTokenRegardlessOfHowTheMailClientCasedTheAddress() {
        Optional<String> token = DiagnosticsService.replyTokenFrom(
                List.of(GOLDEN_REPLY_ADDRESS.toUpperCase(java.util.Locale.ROOT)));

        assertThat(token).contains(GOLDEN_REPLY_TOKEN);
    }
}
