import pytest
from pydantic import ValidationError

from app.models import ConsultationClassificationRequest, ConsultationClassificationResponse


def test_request_accepts_route_specific_urgency_levels_and_descriptions() -> None:
    request = ConsultationClassificationRequest.model_validate(
        {
            "email": {"subject": "Consulta", "sender": "cliente@example.com"},
            "routes": [
                {
                    "routeIndex": 0,
                    "name": "Laboral",
                    "description": "Despidos y contratos laborales",
                    "urgencyLevels": ["BAJA", "MEDIA", "ALTA"],
                }
            ],
            "systemPrompt": "Clasifica.",
        }
    )

    assert request.routes[0].description == "Despidos y contratos laborales"
    assert request.routes[0].urgencyLevels == ["BAJA", "MEDIA", "ALTA"]


@pytest.mark.parametrize("levels", [["NORMAL", ""], ["NORMAL", "NORMAL"], []])
def test_request_rejects_invalid_route_urgency_levels(levels: list[str]) -> None:
    with pytest.raises(ValidationError):
        ConsultationClassificationRequest.model_validate(
            {
                "email": {"subject": "Consulta"},
                "routes": [{"routeIndex": 0, "name": "Laboral", "urgencyLevels": levels}],
                "systemPrompt": "Clasifica.",
            }
        )


def test_response_schema_rejects_extra_model_text() -> None:
    with pytest.raises(ValidationError):
        ConsultationClassificationResponse.model_validate(
            {
                "routeIndex": 0,
                "consultationType": "Laboral",
                "urgency": "URGENT",
                "concept": "Despido",
                "summary": "Consulta laboral.",
                "clientName": "Ana",
                "explanation": "Coincide con laboral.",
                "confidence": 0.9,
                "freeForm": "not allowed",
            }
        )
