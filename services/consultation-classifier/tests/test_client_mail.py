import pytest
from fastapi.testclient import TestClient

from app.gemini_client import ClassifierError, GeminiConsultationClassifier
from app.main import app
from app.models import ClientMailIntentRequest
from jev_fake import FakeJev

# Golden example pinning the intake-orchestrator <-> classifier client-mail contract.
# The same literals are asserted in the intake service's ClientMailIntentContractTests.
GOLDEN_CLIENT_MAIL_REQUEST = {
    "subject": "Como va mi contrato?",
    "body": "Hola, queria saber en que va la revision de mi contrato.",
}

GOLDEN_CLIENT_MAIL_RESPONSE = {"intent": "status_inquiry", "confidence": 0.93}


class ExplodingGemini:
    """Any Gemini call on this path is a bug: the intent is Jev's alone."""

    @property
    def models(self) -> object:
        raise AssertionError("Gemini must not be called to classify client mail")


def classifier_with(jev: FakeJev) -> GeminiConsultationClassifier:
    classifier = GeminiConsultationClassifier()
    classifier.client = ExplodingGemini()
    classifier.jev = jev
    return classifier


def jev_intent(intent: str, confidence: float = 0.93) -> FakeJev:
    return FakeJev({"intent": {"choice": intent, "confidence": confidence}})


@pytest.mark.parametrize("intent", ["status_inquiry", "needs_lawyer"])
def test_jev_decides_the_intent_with_no_gemini_call(intent: str) -> None:
    jev = jev_intent(intent, 0.61)

    response = classifier_with(jev).client_mail_intent(ClientMailIntentRequest.model_validate(GOLDEN_CLIENT_MAIL_REQUEST))

    assert (response.intent, response.confidence) == (intent, 0.61)
    [call] = jev.calls
    assert call["state"] == GOLDEN_CLIENT_MAIL_REQUEST
    assert set(call["questions"]["intent"].criteria) == {"status_inquiry", "needs_lawyer"}


def test_an_answer_outside_the_two_choices_is_an_invalid_response() -> None:
    with pytest.raises(ClassifierError) as error:
        classifier_with(jev_intent("maybe")).client_mail_intent(
            ClientMailIntentRequest.model_validate(GOLDEN_CLIENT_MAIL_REQUEST)
        )

    assert error.value.error == "jev_invalid_response"


def test_a_missing_answer_is_an_invalid_response() -> None:
    with pytest.raises(ClassifierError) as error:
        classifier_with(FakeJev({})).client_mail_intent(ClientMailIntentRequest.model_validate(GOLDEN_CLIENT_MAIL_REQUEST))

    assert error.value.error == "jev_invalid_response"


def test_endpoint_returns_the_golden_response(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("app.main.classifier", classifier_with(jev_intent("status_inquiry")))

    response = TestClient(app).post("/classify-client-mail", json=GOLDEN_CLIENT_MAIL_REQUEST)

    assert response.status_code == 200
    assert response.json() == GOLDEN_CLIENT_MAIL_RESPONSE


def test_endpoint_maps_a_jev_outage_to_service_unavailable(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("app.main.classifier", classifier_with(FakeJev(RuntimeError("typesafe down"))))

    response = TestClient(app).post("/classify-client-mail", json=GOLDEN_CLIENT_MAIL_REQUEST)

    assert response.status_code == 503
    assert response.json()["detail"]["error"] == "jev_unavailable"


def test_endpoint_maps_an_unusable_answer_to_bad_gateway(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("app.main.classifier", classifier_with(jev_intent("maybe")))

    response = TestClient(app).post("/classify-client-mail", json=GOLDEN_CLIENT_MAIL_REQUEST)

    assert response.status_code == 502
    assert response.json()["detail"]["error"] == "jev_invalid_response"


def test_endpoint_without_a_jev_key_is_unavailable(monkeypatch: pytest.MonkeyPatch) -> None:
    classifier = classifier_with(jev_intent("status_inquiry"))
    classifier.jev = None
    monkeypatch.setattr("app.main.classifier", classifier)

    response = TestClient(app).post("/classify-client-mail", json=GOLDEN_CLIENT_MAIL_REQUEST)

    assert response.status_code == 503
