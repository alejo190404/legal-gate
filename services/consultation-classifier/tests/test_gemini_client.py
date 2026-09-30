import json
import logging

import pytest
from google.genai import types

from fastapi.testclient import TestClient

from app.gemini_client import ClassifierError, GeminiConsultationClassifier
from app.main import app
from app.models import ConsultationClassificationRequest
from jev_fake import FakeJev


def jev_routing(route: str = "0", urgencies: dict[str, float] | None = None, confidence: float = 0.8) -> FakeJev:
    answers = {"route": {"choice": route, "confidence": confidence}}
    for index, score in (urgencies or {"0": 0.0}).items():
        answers[f"urgency_{index}"] = {"score": score, "confidence": 0.7}
    return FakeJev(answers)


def new_classifier() -> GeminiConsultationClassifier:
    classifier = GeminiConsultationClassifier()
    classifier.jev = jev_routing()
    return classifier


class FakeInteractions:
    def __init__(self, text: str) -> None:
        self.text = text

    def create(self, **_: object) -> object:
        return type("GeminiResult", (), {"text": self.text})()


class FakeClient:
    def __init__(self, text: str) -> None:
        self.interactions = FakeInteractions(text)


class FakeModels:
    def __init__(self, text: str) -> None:
        self.text = text
        self.last_config = None
        self.last_contents = None

    def generate_content(self, **kwargs: object) -> object:
        self.last_config = kwargs.get("config")
        self.last_contents = kwargs.get("contents")
        return type("GeminiResult", (), {"text": self.text})()


class FakeModelsClient:
    def __init__(self, text: str) -> None:
        self.models = FakeModels(text)


def request() -> ConsultationClassificationRequest:
    return ConsultationClassificationRequest.model_validate(
        {
            "email": {"subject": "Consulta", "sender": "Ana <ana@example.com>"},
            "routes": [
                {
                    "routeIndex": 0,
                    "name": "Laboral",
                    "description": "Despidos y contratos laborales",
                    "destinationEmail": "laboral@example.com",
                    "urgencyLevels": ["NORMAL", "URGENT"],
                }
            ],
            "systemPrompt": "Clasifica.",
        }
    )


def test_parses_structured_gemini_response() -> None:
    payload = {
        "routeIndex": 0,
        "consultationType": "Laboral",
        "urgency": "URGENT",
        "concept": "Terminacion laboral",
        "summary": "Cliente necesita asesoria laboral.",
        "clientName": "Ana",
        "explanation": "La ruta laboral es la mejor coincidencia.",
        "confidence": 0.91,
    }
    classifier = new_classifier()
    classifier.client = FakeClient(json.dumps(payload))

    response = classifier.classify(request())

    assert response.concept == "Terminacion laboral"
    assert response.clientName == "Ana"


def test_parses_structured_response_from_models_api() -> None:
    payload = {
        "routeIndex": 0,
        "consultationType": "Laboral",
        "urgency": "NORMAL",
        "concept": "Contrato",
        "summary": "Cliente necesita revisar un contrato.",
        "clientName": "Ana",
        "explanation": "La ruta laboral es la mejor coincidencia.",
        "confidence": 0.82,
    }
    classifier = new_classifier()
    classifier.client = FakeModelsClient(json.dumps(payload))

    response = classifier.classify(request())

    assert response.urgency == "NORMAL"
    assert response.summary == "Cliente necesita revisar un contrato."


def test_models_api_uses_configured_temperature(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("GEMINI_TEMPERATURE", "0.05")
    payload = {
        "routeIndex": 0,
        "consultationType": "Laboral",
        "urgency": "NORMAL",
        "concept": "Contrato",
        "summary": "Cliente necesita revisar un contrato.",
        "clientName": "Ana",
        "explanation": "La ruta laboral es la mejor coincidencia.",
        "confidence": 0.82,
    }
    fake_client = FakeModelsClient(json.dumps(payload))
    classifier = new_classifier()
    classifier.client = fake_client

    classifier.classify(request())

    assert isinstance(fake_client.models.last_config, types.GenerateContentConfig)
    assert fake_client.models.last_config.temperature == 0.05


def test_models_api_strips_unsupported_schema_fields() -> None:
    payload = {
        "routeIndex": 0,
        "consultationType": "Laboral",
        "urgency": "NORMAL",
        "concept": "Contrato",
        "summary": "Cliente necesita revisar un contrato.",
        "clientName": "Ana",
        "explanation": "La ruta laboral es la mejor coincidencia.",
        "confidence": 0.82,
    }
    fake_client = FakeModelsClient(json.dumps(payload))
    classifier = new_classifier()
    classifier.client = fake_client

    classifier.classify(request())

    schema = fake_client.models.last_config.response_schema
    assert "additionalProperties" not in json.dumps(schema)
    assert "additional_properties" not in json.dumps(schema)


def test_logs_model_call_and_success(caplog: pytest.LogCaptureFixture) -> None:
    payload = {
        "routeIndex": 0,
        "consultationType": "Laboral",
        "urgency": "NORMAL",
        "concept": "Contrato",
        "summary": "Cliente necesita revisar un contrato.",
        "clientName": "Ana",
        "explanation": "La ruta laboral es la mejor coincidencia.",
        "confidence": 0.82,
    }
    classifier = new_classifier()
    classifier.client = FakeModelsClient(json.dumps(payload))

    with caplog.at_level(logging.INFO, logger="legalgate.consultation_classifier"):
        classifier.classify(request())

    messages = [record.getMessage() for record in caplog.records]
    assert any("Classification call starting" in message for message in messages)
    assert any("Gemini classification succeeded" in message for message in messages)


def test_invalid_model_output_surfaces_typed_error() -> None:
    classifier = new_classifier()
    classifier.client = FakeClient("not json")

    with pytest.raises(ClassifierError) as error:
        classifier.classify(request())

    assert error.value.error == "gemini_invalid_response"


class TransientError(Exception):
    def __init__(self, code: int) -> None:
        super().__init__(f"status {code}")
        self.code = code


class FlakyModels:
    def __init__(self, text: str, fail_times: int, code: int) -> None:
        self.text = text
        self.remaining_failures = fail_times
        self.code = code
        self.calls = 0

    def generate_content(self, **_: object) -> object:
        self.calls += 1
        if self.remaining_failures > 0:
            self.remaining_failures -= 1
            raise TransientError(self.code)
        return type("GeminiResult", (), {"text": self.text})()


class FlakyModelsClient:
    def __init__(self, text: str, fail_times: int, code: int) -> None:
        self.models = FlakyModels(text, fail_times, code)


def _ok_payload() -> dict:
    return {
        "routeIndex": 0,
        "consultationType": "Laboral",
        "urgency": "NORMAL",
        "concept": "Contrato",
        "summary": "Cliente necesita revisar un contrato.",
        "clientName": "Ana",
        "explanation": "La ruta laboral es la mejor coincidencia.",
        "confidence": 0.82,
    }


def test_retries_transient_overload_then_succeeds(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("app.gemini_client.time.sleep", lambda _: None)
    classifier = new_classifier()
    classifier.max_retries = 5
    fake = FlakyModelsClient(json.dumps(_ok_payload()), fail_times=3, code=429)
    classifier.client = fake

    response = classifier.classify(request())

    assert response.routeIndex == 0
    assert fake.models.calls == 4  # 3 failures + 1 success


def test_gives_up_after_max_retries(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("app.gemini_client.time.sleep", lambda _: None)
    classifier = new_classifier()
    classifier.max_retries = 2
    fake = FlakyModelsClient(json.dumps(_ok_payload()), fail_times=99, code=503)
    classifier.client = fake

    with pytest.raises(ClassifierError) as error:
        classifier.classify(request())

    assert error.value.error == "gemini_unavailable"
    assert fake.models.calls == 3  # initial + 2 retries


def test_does_not_retry_non_transient_error(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("app.gemini_client.time.sleep", lambda _: None)
    classifier = new_classifier()
    classifier.max_retries = 5
    fake = FlakyModelsClient(json.dumps(_ok_payload()), fail_times=99, code=400)
    classifier.client = fake

    with pytest.raises(ClassifierError):
        classifier.classify(request())

    assert fake.models.calls == 1  # 400 is not retryable


def test_prompt_uses_route_specific_urgency_levels_and_no_tenant_wide_section() -> None:
    classifier = new_classifier()

    prompt = classifier._prompt_for(request(), 0, "NORMAL")

    assert "Tenant routes. Each route defines its own urgencyLevels array ordered low to high" in prompt
    assert "Despidos y contratos laborales" in prompt
    assert "urgencyLevels" in prompt
    assert "Tenant urgency levels" not in prompt



TWO_ROUTES = {
    "email": {"subject": "Me despidieron ayer", "sender": "Ana <ana@example.com>"},
    "routes": [
        {"routeIndex": 0, "name": "Familia", "urgencyLevels": ["NORMAL", "URGENT"]},
        {"routeIndex": 1, "name": "Laboral", "urgencyLevels": ["BAJA", "MEDIA", "ALTA"]},
    ],
    "systemPrompt": "Clasifica.",
}


def serve(monkeypatch: pytest.MonkeyPatch, jev: FakeJev, gemini_payload: dict | None = None) -> FakeModelsClient:
    classifier = GeminiConsultationClassifier()
    classifier.jev = jev
    classifier.client = FakeModelsClient(json.dumps(gemini_payload or {}))
    monkeypatch.setattr("app.main.classifier", classifier)
    return classifier.client


def test_jev_routes_and_takes_urgency_from_the_chosen_routes_own_levels(monkeypatch: pytest.MonkeyPatch) -> None:
    serve(monkeypatch, jev_routing(route="1", urgencies={"0": 0.1, "1": 1.8}, confidence=0.84), _ok_payload())

    response = TestClient(app).post("/classify-consultation", json=TWO_ROUTES)

    assert response.status_code == 200
    body = response.json()
    assert (body["routeIndex"], body["urgency"], body["confidence"]) == (1, "ALTA", 0.84)


def test_classification_takes_text_from_gemini_but_routing_from_jev(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    gemini = serve(
        monkeypatch,
        jev_routing(route="1", urgencies={"0": 1.0, "1": 0.2}),
        {**_ok_payload(), "routeIndex": 0, "urgency": "URGENT", "summary": "Despido sin justa causa."},
    )

    body = TestClient(app).post("/classify-consultation", json=TWO_ROUTES).json()

    assert (body["routeIndex"], body["urgency"]) == (1, "BAJA")
    assert body["summary"] == "Despido sin justa causa."
    assert body["clientName"] == "Ana"
    # Gemini explains the route Jev chose, not one of its own.
    assert "already decided and is final: route 1 (Laboral), urgency BAJA" in gemini.models.last_contents


def test_classification_maps_a_jev_outage_to_service_unavailable(monkeypatch: pytest.MonkeyPatch) -> None:
    serve(monkeypatch, FakeJev(RuntimeError("typesafe down")))

    response = TestClient(app).post("/classify-consultation", json=TWO_ROUTES)

    assert response.status_code == 503
    assert response.json()["detail"]["error"] == "jev_unavailable"
