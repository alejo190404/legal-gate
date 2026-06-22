import json

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
            "routes": [{"routeIndex": 0, "name": "Laboral", "destinationEmail": "laboral@example.com"}],
            "urgencyLevels": ["NORMAL", "URGENT"],
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


def test_invalid_model_output_surfaces_typed_error() -> None:
    classifier = GeminiConsultationClassifier()
    classifier.client = FakeClient("not json")

    with pytest.raises(GeminiClassifierError) as error:
        classifier.classify(request())

    assert error.value.error == "gemini_invalid_response"
