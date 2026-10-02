from __future__ import annotations

import json
import logging
import os
from typing import Any, Literal

from typesafe_sdk import Choice, Score

from .models import ConsultationClassificationRequest, ConsultationDiagnosticsRequest

logger = logging.getLogger("legalgate.consultation_classifier")

# Jev decides; it writes no text. ADR-0006 (intake) records why the decision and the prose are split.
VERDICT_CRITERIA = {
    "accept": "The matter is one the firm takes, and the firm has the information it requires to assess it.",
    "ask": "The matter is one the firm takes, but information the firm requires is still missing.",
    "reject": "The matter is not one the firm takes.",
}


def _jsonable(value: Any) -> Any:
    if hasattr(value, "model_dump"):
        return value.model_dump()
    return getattr(value, "__dict__", str(value))


def _system_one(client: Any, state: Any, questions: dict[str, Any]) -> Any:
    # Same flag that gates the Gemini prompt logs; the state carries the client's email.
    if os.getenv("CLASSIFIER_LOG_PAYLOADS", "false").lower() == "true":
        logger.info(
            "Jev request state=%s questions=%s",
            json.dumps(state, ensure_ascii=False, default=str),
            json.dumps(questions, ensure_ascii=False, default=_jsonable),
        )
    return client.system_one(state=state, questions=questions)


def verdict(client: Any, request: ConsultationDiagnosticsRequest) -> tuple[Literal["accept", "ask", "reject"], float]:
    response = _system_one(
        client,
        state={
            "firmCriteria": request.diagnosticsPrompt,
            "email": request.email.model_dump(),
            "exchange": [message.model_dump() for message in request.exchange],
        },
        questions={
            "verdict": Choice(
                instructions="Given the firm's criteria, decide what happens to this potential client's matter.",
                criteria=VERDICT_CRITERIA,
            )
        },
    )
    answer = response.answers["verdict"]
    return answer.choice, answer.confidence


def route(client: Any, request: ConsultationClassificationRequest) -> tuple[int, str, float]:
    """Routing Rule and Urgency in one request. Each rule's Urgency is scored on that rule's own levels,
    because the levels differ per rule and the rule isn't known until the answers come back."""
    questions: dict[str, Any] = {
        "route": Choice(
            instructions="Which of the firm's routes should handle this consultation.",
            criteria={
                str(r.routeIndex): " - ".join(part for part in (r.name, r.description, ", ".join(r.keywords)) if part)
                for r in request.routes
            },
        )
    }
    for r in request.routes:
        questions[f"urgency_{r.routeIndex}"] = Score(
            instructions=f"How urgent this consultation is for the route {r.name}, from lowest to highest.",
            criteria=list(r.urgencyLevels),
        )
    response = _system_one(client, request.email.model_dump(), questions)
    chosen = response.answers["route"]
    route_index = int(chosen.choice)
    levels = next(r.urgencyLevels for r in request.routes if r.routeIndex == route_index)
    # The score is a probability-weighted average of the levels, so it can land between two of them.
    level = min(max(int(response.answers[f"urgency_{route_index}"].score + 0.5), 0), len(levels) - 1)
    return route_index, levels[level], chosen.confidence
