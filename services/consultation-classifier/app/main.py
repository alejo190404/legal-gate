from __future__ import annotations

from fastapi import FastAPI, HTTPException

from .gemini_client import GeminiClassifierError, GeminiConsultationClassifier
from .models import (
    ConsultationClassificationRequest,
    ConsultationClassificationResponse,
    ConsultationDiagnosticsRequest,
    ConsultationDiagnosticsResponse,
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
        raise _http_error(exc) from exc


@app.post(
    "/diagnose-consultation",
    response_model=ConsultationDiagnosticsResponse,
    responses={502: {"model": ErrorDetail}, 503: {"model": ErrorDetail}},
)
def diagnose_consultation(
    request: ConsultationDiagnosticsRequest,
) -> ConsultationDiagnosticsResponse:
    try:
        return classifier.diagnose(request)
    except GeminiClassifierError as exc:
        raise _http_error(exc) from exc


def _http_error(exc: GeminiClassifierError) -> HTTPException:
    status_code = 503 if exc.error == "gemini_unavailable" else 502
    return HTTPException(
        status_code=status_code,
        detail={"error": exc.error, "message": exc.message, "raw": exc.raw},
    )
