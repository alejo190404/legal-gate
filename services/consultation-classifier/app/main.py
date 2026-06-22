from __future__ import annotations

from fastapi import FastAPI, HTTPException

from .gemini_client import GeminiClassifierError, GeminiConsultationClassifier
from .models import (
    ConsultationClassificationRequest,
    ConsultationClassificationResponse,
    ErrorDetail,
)

app = FastAPI(title="LegalGate Consultation Classifier")
classifier = GeminiConsultationClassifier()


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP", "service": "consultation-classifier"}


@app.post(
    "/classify-consultation",
    response_model=ConsultationClassificationResponse,
    responses={502: {"model": ErrorDetail}, 503: {"model": ErrorDetail}},
)
def classify_consultation(
    request: ConsultationClassificationRequest,
) -> ConsultationClassificationResponse:
    try:
        return classifier.classify(request)
    except GeminiClassifierError as exc:
        status_code = 503 if exc.error == "gemini_unavailable" else 502
        raise HTTPException(
            status_code=status_code,
            detail={"error": exc.error, "message": exc.message, "raw": exc.raw},
        ) from exc
