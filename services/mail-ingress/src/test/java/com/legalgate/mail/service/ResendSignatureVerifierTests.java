package com.legalgate.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.legalgate.mail.config.MailIngressProperties;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The fixture is Svix's own published example — secret, id, timestamp, payload and the signature
 * they document for it. Reproducing it proves the algorithm rather than proving this class agrees
 * with itself.
 */
class ResendSignatureVerifierTests {

    private static final String SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";
    private static final String ID = "msg_p5jXN8AQM9LWM0D4loKWxJek";
    private static final String TIMESTAMP = "1614265330";
    private static final String PAYLOAD = "{\"test\": 2432232314}";
    private static final String SIGNATURE = "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=";

    /** The moment the documented message was signed. */
    private static final Instant SIGNED_AT = Instant.ofEpochSecond(1614265330L);

    private ResendSignatureVerifier verifierWithSecret(String secret) {
        return new ResendSignatureVerifier(new MailIngressProperties(
                null, null,
                new MailIngressProperties.Resend(secret, "re_key"),
                new MailIngressProperties.IntakeOrchestrator(URI.create("http://localhost:8081"), "t"),
                true));
    }

    @Test
    void svixOwnExampleVerifies() {
        assertThat(verifierWithSecret(SECRET).isValid(PAYLOAD, ID, TIMESTAMP, SIGNATURE, SIGNED_AT)).isTrue();
    }

    @Test
    void aTamperedBodyIsRejected() {
        assertThat(verifierWithSecret(SECRET)
                .isValid("{\"test\": 9999999999}", ID, TIMESTAMP, SIGNATURE, SIGNED_AT)).isFalse();
    }

    @Test
    void aSignatureForAnotherMessageIdIsRejected() {
        assertThat(verifierWithSecret(SECRET)
                .isValid(PAYLOAD, "msg_somethingelse", TIMESTAMP, SIGNATURE, SIGNED_AT)).isFalse();
    }

    @Test
    void oneValidEntryAmongSeveralIsEnough() {
        String header = "v1,aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaA= " + SIGNATURE;

        assertThat(verifierWithSecret(SECRET).isValid(PAYLOAD, ID, TIMESTAMP, header, SIGNED_AT)).isTrue();
    }

    @Test
    void aTimestampOlderThanTheToleranceIsRejected() {
        assertThat(verifierWithSecret(SECRET)
                .isValid(PAYLOAD, ID, TIMESTAMP, SIGNATURE, SIGNED_AT.plusSeconds(6 * 60))).isFalse();
    }

    @Test
    void aTimestampTooFarInTheFutureIsRejected() {
        assertThat(verifierWithSecret(SECRET)
                .isValid(PAYLOAD, ID, TIMESTAMP, SIGNATURE, SIGNED_AT.minusSeconds(6 * 60))).isFalse();
    }

    /** The convention the other verifiers follow: no configured secret means local development. */
    @Test
    void aBlankConfiguredSecretDisablesVerification() {
        assertThat(verifierWithSecret("  ").isValid(PAYLOAD, ID, TIMESTAMP, "v1,nonsense", SIGNED_AT)).isTrue();
    }

    @Test
    void aMissingSignatureHeaderIsRejectedWhenASecretIsConfigured() {
        assertThat(verifierWithSecret(SECRET).isValid(PAYLOAD, ID, TIMESTAMP, null, SIGNED_AT)).isFalse();
    }
}
