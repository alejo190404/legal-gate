from __future__ import annotations

from typing import Any, Literal

from typesafe_sdk import Choice, Score

from .models import ConsultationClassificationRequest, ConsultationDiagnosticsRequest

# Jev decides; it writes no text. ADR-0006 (intake) records why the decision and the prose are split.
VERDICT_CRITERIA = {
    "accept": "The matter is one the firm takes, and the firm has the information it requires to assess it.",
    "ask": "The matter is one the firm takes, but information the firm requires is still missing.",
    "reject": "The matter is not one the firm takes.",
}


def verdict(client: Any, request: ConsultationDiagnosticsRequest) -> tuple[Literal["accept", "ask", "reject"], float]:
    response = client.system_one(
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
    response = client.system_one(state=request.email.model_dump(), questions=questions)
    chosen = response.answers["route"]
    route_index = int(chosen.choice)
    levels = next(r.urgencyLevels for r in request.routes if r.routeIndex == route_index)
    # The score is a probability-weighted average of the levels, so it can land between two of them.
    level = min(max(int(response.answers[f"urgency_{route_index}"].score + 0.5), 0), len(levels) - 1)
    return route_index, levels[level], chosen.confidence
