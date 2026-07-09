import json
import logging

import pytest
from google.genai import types

from app.gemini_client import GeminiClassifierError, GeminiConsultationClassifier
from app.models import ConsultationClassificationRequest


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

    def generate_content(self, **kwargs: object) -> object:
        self.last_config = kwargs.get("config")
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
    classifier = GeminiConsultationClassifier()
    classifier.client = FakeClient(json.dumps(payload))

    response = classifier.classify(request())

    assert response.routeIndex == 0
    assert response.urgency == "URGENT"
    assert response.concept == "Terminacion laboral"


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
    classifier = GeminiConsultationClassifier()
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
    classifier = GeminiConsultationClassifier()
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
    classifier = GeminiConsultationClassifier()
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
    classifier = GeminiConsultationClassifier()
    classifier.client = FakeModelsClient(json.dumps(payload))

    with caplog.at_level(logging.INFO, logger="legalgate.consultation_classifier"):
        classifier.classify(request())

    messages = [record.getMessage() for record in caplog.records]
    assert any("Gemini classification call starting" in message for message in messages)
    assert any("Gemini classification succeeded" in message for message in messages)


def test_invalid_model_output_surfaces_typed_error() -> None:
    classifier = GeminiConsultationClassifier()
    classifier.client = FakeClient("not json")

    with pytest.raises(GeminiClassifierError) as error:
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
    classifier = GeminiConsultationClassifier()
    classifier.max_retries = 5
    fake = FlakyModelsClient(json.dumps(_ok_payload()), fail_times=3, code=429)
    classifier.client = fake

    response = classifier.classify(request())

    assert response.routeIndex == 0
    assert fake.models.calls == 4  # 3 failures + 1 success


def test_gives_up_after_max_retries(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("app.gemini_client.time.sleep", lambda _: None)
    classifier = GeminiConsultationClassifier()
    classifier.max_retries = 2
    fake = FlakyModelsClient(json.dumps(_ok_payload()), fail_times=99, code=503)
    classifier.client = fake

    with pytest.raises(GeminiClassifierError) as error:
        classifier.classify(request())

    assert error.value.error == "gemini_unavailable"
    assert fake.models.calls == 3  # initial + 2 retries


def test_does_not_retry_non_transient_error(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("app.gemini_client.time.sleep", lambda _: None)
    classifier = GeminiConsultationClassifier()
    classifier.max_retries = 5
    fake = FlakyModelsClient(json.dumps(_ok_payload()), fail_times=99, code=400)
    classifier.client = fake

    with pytest.raises(GeminiClassifierError):
        classifier.classify(request())

    assert fake.models.calls == 1  # 400 is not retryable


def test_prompt_uses_route_specific_urgency_levels_and_no_tenant_wide_section() -> None:
    classifier = GeminiConsultationClassifier()

    prompt = classifier._prompt_for(request())

    assert "Tenant routes. Each route defines its own urgencyLevels array ordered low to high" in prompt
    assert "Despidos y contratos laborales" in prompt
    assert "urgencyLevels" in prompt
    assert "Tenant urgency levels" not in prompt

