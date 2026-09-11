package com.legalgate.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class QuotedReplyStripperTests {

    private static final String ES_REPLY = "Sí, es un contrato de arrendamiento de local comercial.";
    private static final String EN_REPLY = "Yes, it is a commercial lease.";

    private final QuotedReplyStripper stripper = new QuotedReplyStripper();

    /** One row per shape a real client's mail client produces. */
    static Stream<Arguments> quotedReplyShapes() {
        return Stream.of(
                Arguments.of("gmail es", ES_REPLY + """


                        El mar, 9 sept 2026 a las 10:14, Vargas & Asociados <firma-demo@intake.legal-gate.co> escribió:
                        > ¿Qué tipo de contrato es?
                        > Quedamos atentos.
                        """, ES_REPLY),
                Arguments.of("gmail en", EN_REPLY + """


                        On Tue, Sep 9, 2026 at 10:14 AM Vargas & Asociados <firma-demo@intake.legal-gate.co> wrote:
                        > What kind of contract is it?
                        """, EN_REPLY),
                Arguments.of("apple mail es", ES_REPLY + """


                        El 9 sept 2026, a las 10:14, Vargas & Asociados <firma-demo@intake.legal-gate.co> escribió:

                        ¿Qué tipo de contrato es?
                        """, ES_REPLY),
                Arguments.of("outlook es", ES_REPLY + """


                        De: Vargas & Asociados <firma-demo@intake.legal-gate.co>
                        Enviado el: martes, 9 de septiembre de 2026 10:14
                        Para: Juan Pérez <juan@example.com>
                        Asunto: Re: Su consulta

                        ¿Qué tipo de contrato es?
                        """, ES_REPLY),
                Arguments.of("outlook en", EN_REPLY + """


                        From: Vargas & Asociados <firma-demo@intake.legal-gate.co>
                        Sent: Tuesday, September 9, 2026 10:14 AM
                        To: Juan Pérez <juan@example.com>
                        Subject: Re: Your enquiry

                        What kind of contract is it?
                        """, EN_REPLY),
                Arguments.of("mensaje original es", ES_REPLY + """


                        -----Mensaje original-----
                        De: Vargas & Asociados
                        Asunto: Re: Su consulta
                        """, ES_REPLY),
                Arguments.of("original message en", EN_REPLY + """


                        -----Original Message-----
                        From: Vargas & Asociados
                        """, EN_REPLY),
                Arguments.of("bare angle-quoted run", ES_REPLY + """


                        > ¿Qué tipo de contrato es?
                        > Quedamos atentos.
                        """, ES_REPLY));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("quotedReplyShapes")
    void quotedHistoryIsCutFromTheReply(String shape, String body, String expected) {
        assertThat(stripper.strip(body)).isEqualTo(expected);
    }

    @Test
    void aForwardedThreadWhoseContentIsBelowTheMarkerSurvivesUntouched() {
        String forwarded = """
                El mar, 9 sept 2026 a las 10:14, Juan Pérez <juan@example.com> escribió:
                > Necesito asesoría sobre un despido sin justa causa.
                > Llevo cuatro años en la empresa.
                """;

        assertThat(stripper.strip(forwarded)).isEqualTo(forwarded);
    }

    @Test
    void aBodyThatIsOnlyQuotedHistoryIsKeptRatherThanEmptied() {
        String onlyQuoted = """


                El mar, 9 sept 2026 a las 10:14, Juan Pérez <juan@example.com> escribió:
                > Necesito asesoría sobre un despido sin justa causa.
                """;

        assertThat(stripper.strip(onlyQuoted)).isEqualTo(onlyQuoted);
    }

    /**
     * "De:" opens the Outlook block, but it is also ordinary Spanish. Without the
     * "Enviado el:"/"Para:" line under it, the client is just writing.
     */
    @Test
    void aClientWritingDeIsNotAnOutlookHeaderBlock() {
        String reply = """
                De: mi arrendador recibí una carta de terminación.

                Quisiera saber si es válida.
                """;

        assertThat(stripper.strip(reply)).isEqualTo(reply);
    }
}
