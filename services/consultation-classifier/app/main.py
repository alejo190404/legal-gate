from __future__ import annotations

import logging
import time
from collections.abc import Callable
from typing import TypeVar

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from .gemini_client import GeminiClassifierError, GeminiConsultationClassifier
from .models import (
    ConsultationClassificationRequest,
    ConsultationClassificationResponse,
    ConsultationDiagnosticsRequest,
    ConsultationDiagnosticsResponse,
    ErrorDetail,
)

# uvicorn configures only its own loggers and leaves the root one bare, so without this every
# logger.info in this service falls through to logging.lastResort and is dropped below WARNING.
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)

logger = logging.getLogger("legalgate.consultation_classifier")

T = TypeVar("T", bound=BaseModel)

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
    return _served("classify-consultation", request, lambda: classifier.classify(request))


@app.post(
    "/diagnose-consultation",
    response_model=ConsultationDiagnosticsResponse,
    responses={502: {"model": ErrorDetail}, 503: {"model": ErrorDetail}},
)
def diagnose_consultation(
    request: ConsultationDiagnosticsRequest,
) -> ConsultationDiagnosticsResponse:
    return _served("diagnose-consultation", request, lambda: classifier.diagnose(request))


def _served(endpoint: str, request: BaseModel, call: Callable[[], T]) -> T:
    # Both payloads and the elapsed time go out unconditionally: when intake records a call as
    # unavailable, these lines are what say whether the answer was wrong or merely late.
    logger.info("%s request received payload=%s", endpoint, request.model_dump_json())
    started = time.monotonic()
    try:
        response = call()
    except GeminiClassifierError as exc:
        logger.warning(
            "%s failed elapsed=%.2fs error=%s message=%s",
            endpoint,
            time.monotonic() - started,
            exc.error,
            exc.message,
        )
        raise _http_error(exc) from exc
    logger.info(
        "%s response sent elapsed=%.2fs payload=%s",
        endpoint,
        time.monotonic() - started,
        response.model_dump_json(),
    )
    return response


def _http_error(exc: GeminiClassifierError) -> HTTPException:
    status_code = 503 if exc.error == "gemini_unavailable" else 502
    return HTTPException(
        status_code=status_code,
        detail={"error": exc.error, "message": exc.message, "raw": exc.raw},
    )
