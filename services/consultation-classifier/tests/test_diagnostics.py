import json

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.gemini_client import ClassifierError, GeminiConsultationClassifier
from app.main import app
from app.models import ConsultationDiagnosticsRequest, ConsultationDiagnosticsResponse
from jev_fake import FakeJev, jev_verdict

# Golden example pinning the intake-orchestrator <-> classifier diagnose contract.
# The same literal is asserted as serialized output in the intake service tests
# (DiagnosticsContractTests). Changing a field name here must change it there too.
GOLDEN_DIAGNOSE_REQUEST = {
    "diagnosticsPrompt": "Tomamos casos laborales. Necesitamos la fecha del despido y el tipo de contrato.",
    "email": {
        "subject": "Consulta laboral",
        "plain": "Me despidieron.",
        "html": None,
        "sender": "Maria Perez <maria@example.com>",
        "recipients": ["firma-demo@intake.legal-gate.co"],
        "messageId": "<message-123@example.com>",
    },
    "exchange": [
        {"role": "LEGALGATE", "body": "Cual fue la fecha del despido?"},
        {"role": "CLIENT", "body": "El 3 de marzo."},
    ],
    "systemPrompt": "Decide si la firma toma el caso.",
    "promptVersion": "consultation-diagnostics-v2",
}

GOLDEN_DIAGNOSE_RESPONSE = {
    "verdict": "ask",
    "question": "Que tipo de contrato tenia?",
    "acknowledgment": "el despido en su trabajo",
    "reason": "Falta el tipo de contrato.",
    "summary": "Despido el 3 de marzo.",
}


class FakeModels:
    def __init__(self, text: str) -> None:
        self.text = text
        self.prompts: list[str] = []

    def generate_content(self, **kwargs: object) -> object:
        self.prompts.append(str(kwargs["contents"]))
        return type("GeminiResult", (), {"text": self.text})()


class FakeClient:
    def __init__(self, text: str) -> None:
        self.models = FakeModels(text)


def classifier_returning(payload: object, verdict: str = "ask", confidence: float = 0.9) -> GeminiConsultationClassifier:
    """Jev decides `verdict`; Gemini, when called, writes `payload`."""
    classifier = GeminiConsultationClassifier()
    classifier.client = FakeClient(payload if isinstance(payload, str) else json.dumps(payload))
    classifier.jev = jev_verdict(verdict, confidence)
    return classifier


def gemini_prompts(classifier: GeminiConsultationClassifier) -> list[str]:
    return classifier.client.models.prompts


def test_reject_is_decided_by_jev_without_calling_gemini() -> None:
    classifier = classifier_returning(GOLDEN_DIAGNOSE_RESPONSE, verdict="reject", confidence=0.91)

    response = classifier.diagnose(ConsultationDiagnosticsRequest.model_validate(GOLDEN_DIAGNOSE_REQUEST))

    assert response.verdict == "reject"
    assert response.question is None
    assert "0.91" in response.reason
    assert response.summary == "Consulta laboral"
    assert gemini_prompts(classifier) == []


def test_accepts_the_golden_request_payload() -> None:
    request = ConsultationDiagnosticsRequest.model_validate(GOLDEN_DIAGNOSE_REQUEST)

    assert request.diagnosticsPrompt.startswith("Tomamos casos laborales")
    assert [message.role for message in request.exchange] == ["LEGALGATE", "CLIENT"]
    assert request.email.recipients == ["firma-demo@intake.legal-gate.co"]


def test_diagnose_returns_ask_verdict_with_question() -> None:
    response = classifier_returning(GOLDEN_DIAGNOSE_RESPONSE).diagnose(
        ConsultationDiagnosticsRequest.model_validate(GOLDEN_DIAGNOSE_REQUEST)
    )

    assert response.verdict == "ask"
    assert response.question == "Que tipo de contrato tenia?"
    assert response.acknowledgment == "el despido en su trabajo"
    assert response.summary == "Despido el 3 de marzo."


def test_diagnose_carries_a_missing_acknowledgment_as_none_rather_than_failing() -> None:
    payload = {key: value for key, value in GOLDEN_DIAGNOSE_RESPONSE.items() if key != "acknowledgment"}

    response = classifier_returning(payload).diagnose(
        ConsultationDiagnosticsRequest.model_validate(GOLDEN_DIAGNOSE_REQUEST)
    )

    assert response.acknowledgment is None
    assert response.verdict == "ask"


def test_accept_takes_the_verdict_from_jev_and_the_text_from_gemini() -> None:
    # Gemini writes a question and names a different verdict; neither may reach the response.
    classifier = classifier_returning(
        {"verdict": "reject", "question": "por que?", "reason": "Tiene lo necesario.", "summary": "Despido."},
        verdict="accept",
    )

    response = classifier.diagnose(ConsultationDiagnosticsRequest.model_validate(GOLDEN_DIAGNOSE_REQUEST))

    assert response.verdict == "accept"
    assert response.question is None
    assert response.reason == "Tiene lo necesario."
    assert response.summary == "Despido."
    [prompt] = gemini_prompts(classifier)
    assert "already decided and is final: accept" in prompt


def test_ask_tells_gemini_the_verdict_it_is_writing_for() -> None:
    classifier = classifier_returning(GOLDEN_DIAGNOSE_RESPONSE, verdict="ask")

    response = classifier.diagnose(ConsultationDiagnosticsRequest.model_validate(GOLDEN_DIAGNOSE_REQUEST))

    assert (response.verdict, response.question) == ("ask", "Que tipo de contrato tenia?")
    [prompt] = gemini_prompts(classifier)
    assert "already decided and is final: ask" in prompt


def test_diagnose_surfaces_non_json_model_output_as_a_service_failure() -> None:
    with pytest.raises(ClassifierError) as error:
        classifier_returning("no soy json").diagnose(
            ConsultationDiagnosticsRequest.model_validate(GOLDEN_DIAGNOSE_REQUEST)
        )

    assert error.value.error == "gemini_invalid_response"


@pytest.mark.parametrize(
    "payload",
    [
        {"reason": "r", "summary": "s"},
        {"question": "Que contrato?", "reason": "", "summary": "s"},
        {"question": "Que contrato?", "reason": "r"},
    ],
)
def test_diagnose_rejects_text_that_does_not_fit_an_ask(payload: dict) -> None:
    with pytest.raises(ClassifierError) as error:
        classifier_returning(payload, verdict="ask").diagnose(
            ConsultationDiagnosticsRequest.model_validate(GOLDEN_DIAGNOSE_REQUEST)
        )

    assert error.value.error == "gemini_invalid_response"


def test_diagnose_without_a_gemini_key_is_reported_as_unavailable() -> None:
    classifier = classifier_returning(GOLDEN_DIAGNOSE_RESPONSE, verdict="ask")
    classifier.client = None

    with pytest.raises(ClassifierError) as error:
        classifier.diagnose(ConsultationDiagnosticsRequest.model_validate(GOLDEN_DIAGNOSE_REQUEST))

    assert error.value.error == "gemini_unavailable"


def test_diagnose_without_a_jev_key_is_reported_as_unavailable() -> None:
    classifier = classifier_returning(GOLDEN_DIAGNOSE_RESPONSE)
    classifier.jev = None

    with pytest.raises(ClassifierError) as error:
        classifier.diagnose(ConsultationDiagnosticsRequest.model_validate(GOLDEN_DIAGNOSE_REQUEST))

    assert error.value.error == "jev_unavailable"


def test_endpoint_maps_a_jev_outage_to_service_unavailable_without_falling_back_to_gemini(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    classifier = classifier_returning(GOLDEN_DIAGNOSE_RESPONSE)
    classifier.jev = FakeJev(RuntimeError("typesafe down"))
    monkeypatch.setattr("app.main.classifier", classifier)

    response = TestClient(app).post("/diagnose-consultation", json=GOLDEN_DIAGNOSE_REQUEST)

    assert response.status_code == 503
    assert response.json()["detail"]["error"] == "jev_unavailable"
    assert gemini_prompts(classifier) == []


def test_response_model_rejects_blank_reason() -> None:
    with pytest.raises(ValidationError):
        ConsultationDiagnosticsResponse.model_validate(
            {"verdict": "accept", "reason": "", "summary": "s"}
        )


def test_endpoint_maps_invalid_model_output_to_bad_gateway(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("app.main.classifier", classifier_returning("no soy json"))

    response = TestClient(app).post("/diagnose-consultation", json=GOLDEN_DIAGNOSE_REQUEST)

    assert response.status_code == 502
    assert response.json()["detail"]["error"] == "gemini_invalid_response"


def test_endpoint_returns_the_golden_response(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("app.main.classifier", classifier_returning(GOLDEN_DIAGNOSE_RESPONSE))

    response = TestClient(app).post("/diagnose-consultation", json=GOLDEN_DIAGNOSE_REQUEST)

    assert response.status_code == 200
    assert response.json() == GOLDEN_DIAGNOSE_RESPONSE


def test_endpoint_logs_the_request_and_the_response(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    monkeypatch.setattr("app.main.classifier", classifier_returning(GOLDEN_DIAGNOSE_RESPONSE))

    with caplog.at_level("INFO", logger="legalgate.consultation_classifier"):
        TestClient(app).post("/diagnose-consultation", json=GOLDEN_DIAGNOSE_REQUEST)

    logged = "\n".join(record.getMessage() for record in caplog.records)
    assert "diagnose-consultation request received" in logged
    assert "Tomamos casos laborales" in logged
    assert "diagnose-consultation response sent" in logged
    assert "Que tipo de contrato tenia?" in logged
    assert "elapsed=" in logged


def test_endpoint_logs_a_failure_with_its_cause(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    monkeypatch.setattr("app.main.classifier", classifier_returning("no soy json"))

    with caplog.at_level("INFO", logger="legalgate.consultation_classifier"):
        TestClient(app).post("/diagnose-consultation", json=GOLDEN_DIAGNOSE_REQUEST)

    logged = "\n".join(record.getMessage() for record in caplog.records)
    assert "diagnose-consultation failed" in logged
    assert "error=gemini_invalid_response" in logged
