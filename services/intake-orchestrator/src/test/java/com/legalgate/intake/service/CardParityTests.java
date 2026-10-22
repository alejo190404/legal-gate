package com.legalgate.intake.service;

import static com.legalgate.intake.service.CardParity.assertPlaintextCarriesTheCard;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CardParityTests {

    private static final String CARD = """
            <!DOCTYPE html><html><head><title>Mensaje recibido</title><style>a{color:red;}</style></head>
            <body>
              <div style="display:none;max-height:0;">Vista previa que nadie lee en la tarjeta.</div>
              <!--[if mso]><p>Solo Outlook.</p><![endif]-->
              <table><tr><td>&#10003;</td></tr>
                <tr><td><div>Mensaje recibido</div>
                  <div>Recibimos su mensaje y lo trasladamos a su abogado(a), quien le responder&aacute;
                    directamente. <b>Gracias.</b></div>
                  <div style="white-space:pre-line;">Primera l&iacute;nea
            segunda l&iacute;nea</div>
                  <div>12 <span>marzo</span></div></td></tr></table>
            </body></html>
            """;

    @Test
    void aPlaintextPartWithEverySentencePassesHoweverItWrapsThem() {
        String text = """
                Mensaje recibido

                Recibimos su mensaje y lo trasladamos a su abogado(a),
                quien le responderá directamente. Gracias.

                Primera línea segunda línea
                12 marzo
                """;

        assertThatCode(() -> assertPlaintextCarriesTheCard(new RenderedEmail(text, CARD))).doesNotThrowAnyException();
    }

    @Test
    void aSentenceMissingFromThePlaintextFailsAndIsNamed() {
        String text = "Mensaje recibido\nRecibimos su mensaje y lo trasladamos a su abogado(a), quien le responderá directamente.\n"
                + "Primera línea segunda línea\n12 marzo";

        assertThatThrownBy(() -> assertPlaintextCarriesTheCard(new RenderedEmail(text, CARD)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Gracias.");
    }

    @Test
    void aPurelyVisualPieceCanBeExcusedByName() {
        String text = "Mensaje recibido\nRecibimos su mensaje y lo trasladamos a su abogado(a), quien le responderá directamente. "
                + "Gracias.\nPrimera línea segunda línea";

        assertThatThrownBy(() -> assertPlaintextCarriesTheCard(new RenderedEmail(text, CARD)))
                .hasMessageContaining("12 marzo");
        assertThatCode(() -> assertPlaintextCarriesTheCard(new RenderedEmail(text, CARD), "12 marzo"))
                .doesNotThrowAnyException();
    }
}
