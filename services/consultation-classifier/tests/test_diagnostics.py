import json

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.gemini_client import GeminiClassifierError, GeminiConsultationClassifier
from app.main import app
from app.models import ConsultationDiagnosticsRequest, ConsultationDiagnosticsResponse

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

    def generate_content(self, **_: object) -> object:
        return type("GeminiResult", (), {"text": self.text})()


class FakeClient:
    def __init__(self, text: str) -> None:
        self.models = FakeModels(text)


def classifier_returning(payload: object) -> GeminiConsultationClassifier:
    classifier = GeminiConsultationClassifier()
    classifier.client = FakeClient(payload if isinstance(payload, str) else json.dumps(payload))
    return classifier


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


@pytest.mark.parametrize("verdict", ["accept", "reject"])
def test_diagnose_returns_terminal_verdicts_without_a_question(verdict: str) -> None:
    payload = {
        "verdict": verdict,
        "question": None,
        "reason": "Razon del veredicto.",
        "summary": "Resumen del asunto.",
    }

    response = classifier_returning(payload).diagnose(
        ConsultationDiagnosticsRequest.model_validate(GOLDEN_DIAGNOSE_REQUEST)
    )

    assert response.verdict == verdict
    assert response.question is None


def test_diagnose_surfaces_non_json_model_output_as_a_service_failure() -> None:
    with pytest.raises(GeminiClassifierError) as error:
        classifier_returning("no soy json").diagnose(
            ConsultationDiagnosticsRequest.model_validate(GOLDEN_DIAGNOSE_REQUEST)
        )

    assert error.value.error == "gemini_invalid_response"


@pytest.mark.parametrize(
    "payload",
    [
        {"verdict": "maybe", "reason": "r", "summary": "s"},
        {"verdict": "ask", "reason": "r", "summary": "s"},
        {"verdict": "accept", "question": "por que?", "reason": "r", "summary": "s"},
        {"verdict": "accept", "reason": "r", "summary": "s", "extra": "no permitido"},
    ],
)
def test_diagnose_rejects_responses_that_fail_validation(payload: dict) -> None:
    with pytest.raises(GeminiClassifierError) as error:
        classifier_returning(payload).diagnose(
            ConsultationDiagnosticsRequest.model_validate(GOLDEN_DIAGNOSE_REQUEST)
        )

    assert error.value.error == "gemini_invalid_response"


def test_diagnose_without_an_api_key_is_reported_as_unavailable() -> None:
    classifier = GeminiConsultationClassifier()
    classifier.client = None

    with pytest.raises(GeminiClassifierError) as error:
        classifier.diagnose(ConsultationDiagnosticsRequest.model_validate(GOLDEN_DIAGNOSE_REQUEST))

    assert error.value.error == "gemini_unavailable"


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
