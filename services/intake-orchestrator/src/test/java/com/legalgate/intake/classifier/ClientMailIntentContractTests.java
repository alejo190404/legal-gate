package com.legalgate.intake.classifier;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalgate.intake.config.IntakeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.client.MockServerRestTemplateCustomizer;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

/**
 * Golden examples pinning the client-mail intent contract. The same literals are asserted in
 * consultation-classifier/tests/test_client_mail.py.
 */
class ClientMailIntentContractTests {

    private static final String GOLDEN_CLIENT_MAIL_REQUEST = """
            {"subject": "Como va mi contrato?", "body": "Hola, queria saber en que va la revision de mi contrato."}
            """;

    private static final String GOLDEN_CLIENT_MAIL_RESPONSE = """
            {"intent": "status_inquiry", "confidence": 0.93}
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MockServerRestTemplateCustomizer mockServer = new MockServerRestTemplateCustomizer();
    private final HttpConsultationClassifierClient client =
            new HttpConsultationClassifierClient(properties(), new RestTemplateBuilder(mockServer));
    private final MockRestServiceServer server = mockServer.getServer();

    @Test
    void serializesTheRequestExactlyAsTheClassifierExpectsIt() throws Exception {
        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(golden())))
                .isEqualTo(objectMapper.readTree(GOLDEN_CLIENT_MAIL_REQUEST));
    }

    @Test
    void postsToTheIntentEndpointAndReadsTheGoldenResponse() {
        server.expect(requestTo("http://classifier:8083/classify-client-mail"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json(GOLDEN_CLIENT_MAIL_REQUEST, true))
                .andRespond(withSuccess(GOLDEN_CLIENT_MAIL_RESPONSE, MediaType.APPLICATION_JSON));

        ClientMailIntentResponse response = client.classifyClientMail(golden());

        assertThat(response.isStatusInquiry()).isTrue();
        assertThat(response.confidence()).isEqualTo(0.93);
        server.verify();
    }

    @Test
    void aClassifierOutageIsTheExistingUnavailableFailure() {
        server.expect(requestTo("http://classifier:8083/classify-client-mail"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.classifyClientMail(golden()))
                .isInstanceOf(ClassifierUnavailableException.class);
    }

    @Test
    void anAnswerOutsideTheTwoIntentsIsUnavailableToo() {
        server.expect(requestTo("http://classifier:8083/classify-client-mail"))
                .andRespond(withSuccess("{\"intent\": \"maybe\", \"confidence\": 0.5}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.classifyClientMail(golden()))
                .isInstanceOf(ClassifierUnavailableException.class)
                .hasMessage("consultation_classifier_unusable_response");
    }

    private ClientMailIntentRequest golden() {
        return new ClientMailIntentRequest("Como va mi contrato?", "Hola, queria saber en que va la revision de mi contrato.");
    }

    private static IntakeProperties properties() {
        return new IntakeProperties(
                "memory", false, "intake.legal-gate.co", "http://classifier:8083/", null, null, null, null, null,
                false, null, null, null, null, false, "test-token", "test-key", null, null, null);
    }
}
