from __future__ import annotations

import json
import logging
import os
import random
import time
import uuid
from collections.abc import Callable
from typing import Any, TypeVar

from google import genai
from google.genai import types
from pydantic import BaseModel, ValidationError
from typesafe_sdk import TypeSafeClient

from . import jev_client
from .models import (
    ConsultationClassificationRequest,
    ConsultationClassificationResponse,
    ConsultationDiagnosticsRequest,
    ClassificationText,
    ConsultationDiagnosticsResponse,
    DiagnosticsText,
)

T = TypeVar("T", bound=BaseModel)
R = TypeVar("R")

logger = logging.getLogger("legalgate.consultation_classifier")

# Transient Gemini failures worth retrying: rate limit + server-side overload.
# ponytail: hardcoded set, move to env if Gemini adds codes worth tuning per-deploy.
RETRYABLE_STATUS_CODES = frozenset({429, 500, 502, 503, 504})


class ClassifierError(RuntimeError):
    def __init__(self, error: str, message: str, raw: Any | None = None) -> None:
        super().__init__(message)
        self.error = error
        self.message = message
        self.raw = raw


class GeminiConsultationClassifier:
    def __init__(self) -> None:
        self.api_key = os.getenv("GEMINI_API_KEY")
        self.model = os.getenv("GEMINI_MODEL", "gemini-3.5-flash")
        self.temperature = self._temperature_from_env()
        self.max_retries = self._int_from_env("GEMINI_MAX_RETRIES", 5)
        self.retry_base_delay = self._float_from_env("GEMINI_RETRY_BASE_DELAY", 0.5)
        self.log_payloads = os.getenv("CLASSIFIER_LOG_PAYLOADS", "false").lower() == "true"
        self.log_preview_chars = self._int_from_env("CLASSIFIER_LOG_PREVIEW_CHARS", 500)
        self.client = genai.Client(api_key=self.api_key) if self.api_key else None
        jev_api_key = os.getenv("TYPESAFE_API_KEY")
        self.jev = TypeSafeClient(api_key=jev_api_key) if jev_api_key else None

    def classify(
        self,
        request: ConsultationClassificationRequest,
    ) -> ConsultationClassificationResponse:
        classification_request_id = str(uuid.uuid4())
        self._log_request_start(classification_request_id, request)
        route_index, urgency, confidence = self._jev(
            classification_request_id, lambda client: jev_client.route(client, request)
        )
        logger.info(
            "Jev classification request_id=%s routeIndex=%s urgency=%s confidence=%.2f",
            classification_request_id,
            route_index,
            urgency,
            confidence,
        )
        if self.client is None:
            logger.warning(
                "Gemini classification skipped request_id=%s reason=missing_api_key",
                classification_request_id,
            )
            raise ClassifierError(
                "gemini_unavailable",
                "GEMINI_API_KEY is not configured for consultation-classifier.",
            )

        text = self._complete(
            classification_request_id,
            self._prompt_for(request, route_index, urgency),
            ClassificationText,
        )
        logger.info(
            "Gemini classification succeeded request_id=%s model=%s concept=%s",
            classification_request_id,
            self.model,
            text.concept,
        )
        return ConsultationClassificationResponse(
            routeIndex=route_index, urgency=urgency, confidence=confidence, **text.model_dump()
        )

    def diagnose(
        self,
        request: ConsultationDiagnosticsRequest,
    ) -> ConsultationDiagnosticsResponse:
        diagnostics_request_id = str(uuid.uuid4())
        logger.info(
            "Diagnostics call starting request_id=%s model=%s promptVersion=%s messageId=%s "
            "sender=%s exchangeMessages=%s",
            diagnostics_request_id,
            self.model,
            request.promptVersion,
            request.email.messageId,
            request.email.sender,
            len(request.exchange),
        )
        verdict, confidence = self._jev(diagnostics_request_id, lambda client: jev_client.verdict(client, request))
        logger.info("Jev verdict request_id=%s verdict=%s confidence=%.2f", diagnostics_request_id, verdict, confidence)
        if verdict == "reject":
            # No one outside the firm reads a rejection's prose, so it costs no Gemini call.
            return ConsultationDiagnosticsResponse(
                verdict="reject",
                reason=f"No corresponde a las materias que atiende la firma (confianza {confidence:.2f}).",
                summary=self._subject_or_body(request),
            )
        if self.client is None:
            logger.warning(
                "Gemini diagnostics skipped request_id=%s reason=missing_api_key",
                diagnostics_request_id,
            )
            raise ClassifierError(
                "gemini_unavailable",
                "GEMINI_API_KEY is not configured for consultation-classifier.",
            )

        text = self._complete(
            diagnostics_request_id,
            self._diagnostics_prompt_for(request, verdict),
            DiagnosticsText,
        )
        try:
            response = ConsultationDiagnosticsResponse(
                verdict=verdict,
                question=text.question if verdict == "ask" else None,
                acknowledgment=text.acknowledgment,
                reason=text.reason,
                summary=text.summary,
            )
        except ValidationError as exc:
            raise ClassifierError(
                "gemini_invalid_response",
                "Gemini text did not fit the verdict.",
                text.model_dump(),
            ) from exc
        logger.info(
            "Gemini diagnostics succeeded request_id=%s model=%s verdict=%s",
            diagnostics_request_id,
            self.model,
            response.verdict,
        )
        return response

    def _complete(self, request_id: str, prompt: str, response_model: type[T]) -> T:
        if self.log_payloads:
            logger.info(
                "Gemini prompt request_id=%s prompt=%s",
                request_id,
                self._truncate(prompt),
            )
        try:
            result = self._generate_structured_content(prompt, response_model)
        except Exception as exc:
            logger.exception(
                "Gemini call failed request_id=%s model=%s",
                request_id,
                self.model,
            )
            raise ClassifierError("gemini_unavailable", "Gemini request failed.") from exc

        raw = self._extract_text(result)
        if self.log_payloads:
            logger.info(
                "Gemini raw response request_id=%s response=%s",
                request_id,
                self._truncate(raw),
            )
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError as exc:
            logger.warning(
                "Gemini returned non-json request_id=%s model=%s response_preview=%s",
                request_id,
                self.model,
                self._truncate(raw),
            )
            raise ClassifierError(
                "gemini_invalid_response",
                "Gemini returned non-JSON output.",
                raw,
            ) from exc

        try:
            return response_model.model_validate(payload)
        except ValidationError as exc:
            logger.warning(
                "Gemini schema validation failed request_id=%s model=%s payload=%s",
                request_id,
                self.model,
                self._truncate(json.dumps(payload, ensure_ascii=False)),
            )
            raise ClassifierError(
                "gemini_invalid_response",
                "Gemini JSON did not match the expected schema.",
                payload,
            ) from exc

    def _jev(self, request_id: str, call: Callable[[Any], R]) -> R:
        # No Gemini fallback: a Jev outage takes the same retry path a Gemini outage does (ADR-0006).
        if self.jev is None:
            raise ClassifierError(
                "jev_unavailable",
                "TYPESAFE_API_KEY is not configured for consultation-classifier.",
            )
        try:
            return call(self.jev)
        except Exception as exc:
            logger.exception("Jev call failed request_id=%s", request_id)
            raise ClassifierError("jev_unavailable", "Jev request failed.") from exc

    def _subject_or_body(self, request: ConsultationDiagnosticsRequest) -> str:
        email = request.email
        return (email.subject or "").strip() or (email.plain or "").strip()[:200] or "Consulta sin asunto."

    def _diagnostics_prompt_for(self, request: ConsultationDiagnosticsRequest, verdict: str) -> str:
        return "\n\n".join(
            [
                request.systemPrompt,
                "Return only valid JSON matching the provided schema.",
                f"The verdict is already decided and is final: {verdict}. Write the text for it; "
                "do not reconsider the decision."
                + (" Write the question for the potential client." if verdict == "ask" else " Leave question empty."),
                "The firm describes the matters it takes and the information it needs before "
                "assessing one as follows:",
                request.diagnosticsPrompt,
                "Original inbound email:",
                request.email.model_dump_json(),
                "Exchange so far, oldest first (empty on the first pass):",
                json.dumps(
                    [message.model_dump() for message in request.exchange],
                    ensure_ascii=False,
                ),
            ]
        )

    def _prompt_for(self, request: ConsultationClassificationRequest, route_index: int, urgency: str) -> str:
        route_name = next(route.name for route in request.routes if route.routeIndex == route_index)
        return "\n\n".join(
            [
                request.systemPrompt,
                "Return only valid JSON matching the provided schema.",
                f"Routing is already decided and is final: route {route_index} ({route_name}), urgency {urgency}. "
                "Write the text for that route; do not reconsider it.",
                "Tenant routes. Each route defines its own urgencyLevels array ordered low to high and may include a short description:",
                json.dumps(
                    [route.model_dump() for route in request.routes],
                    ensure_ascii=False,
                ),
                "Inbound email:",
                request.email.model_dump_json(),
            ]
        )

    def _extract_text(self, result: Any) -> str:
        text = getattr(result, "text", None)
        if isinstance(text, str) and text.strip():
            return text.strip()
        if isinstance(result, str):
            return result.strip()
        try:
            return result.model_dump_json()
        except AttributeError:
            return json.dumps(result)

    def _generate_structured_content(self, prompt: str, response_model: type[BaseModel]) -> Any:
        schema = self._gemini_response_schema(response_model)
        return self._call_with_retry(prompt, schema)

    def _call_with_retry(self, prompt: str, schema: dict[str, Any]) -> Any:
        # Retry transient overload/rate-limit (free tier) with exponential backoff + jitter.
        attempts = self.max_retries + 1
        for attempt in range(attempts):
            try:
                return self._call_model(prompt, schema)
            except Exception as exc:  # noqa: BLE001 - re-raised unless transient
                if attempt >= self.max_retries or not self._is_retryable(exc):
                    raise
                delay = self.retry_base_delay * (2**attempt) + random.uniform(0, self.retry_base_delay)
                logger.warning(
                    "Gemini call transient failure, retrying attempt=%s/%s delay=%.2fs model=%s error=%s",
                    attempt + 1,
                    self.max_retries,
                    delay,
                    self.model,
                    exc,
                )
                time.sleep(delay)
        raise AssertionError("unreachable retry loop exit")

    def _call_model(self, prompt: str, schema: dict[str, Any]) -> Any:
        if hasattr(self.client, "interactions"):
            return self.client.interactions.create(
                model=self.model,
                input=prompt,
                response_format={"mime_type": "application/json", "schema": schema},
                temperature=self.temperature,
            )
        return self.client.models.generate_content(
            model=self.model,
            contents=prompt,
            config=types.GenerateContentConfig(
                response_mime_type="application/json",
                response_schema=schema,
                temperature=self.temperature,
            ),
        )

    def _is_retryable(self, exc: Exception) -> bool:
        code = getattr(exc, "code", None) or getattr(exc, "status_code", None)
        return code in RETRYABLE_STATUS_CODES

    def _gemini_response_schema(self, response_model: type[BaseModel]) -> dict[str, Any]:
        return self._remove_unsupported_schema_fields(response_model.model_json_schema())

    def _remove_unsupported_schema_fields(self, value: Any) -> Any:
        unsupported_keys = {
            "$defs",
            "$schema",
            "additionalProperties",
            "default",
            "examples",
            "title",
        }
        if isinstance(value, dict):
            return {
                key: self._remove_unsupported_schema_fields(item)
                for key, item in value.items()
                if key not in unsupported_keys
            }
        if isinstance(value, list):
            return [self._remove_unsupported_schema_fields(item) for item in value]
        return value

    def _temperature_from_env(self) -> float:
        raw_value = os.getenv("GEMINI_TEMPERATURE", "0.2")
        try:
            temperature = float(raw_value)
        except ValueError as exc:
            raise ValueError("GEMINI_TEMPERATURE must be a number between 0 and 2.") from exc
        if temperature < 0 or temperature > 2:
            raise ValueError("GEMINI_TEMPERATURE must be between 0 and 2.")
        return temperature

    def _int_from_env(self, name: str, default: int) -> int:
        raw_value = os.getenv(name, str(default))
        try:
            value = int(raw_value)
        except ValueError as exc:
            raise ValueError(f"{name} must be an integer.") from exc
        return max(0, value)

    def _float_from_env(self, name: str, default: float) -> float:
        raw_value = os.getenv(name, str(default))
        try:
            value = float(raw_value)
        except ValueError as exc:
            raise ValueError(f"{name} must be a number.") from exc
        return max(0.0, value)

    def _log_request_start(
        self,
        request_id: str,
        request: ConsultationClassificationRequest,
    ) -> None:
        email = request.email
        routes = [
            {
                "routeIndex": route.routeIndex,
                "name": route.name,
                "description": route.description,
                "destinationEmail": route.destinationEmail,
                "keywords": route.keywords,
                "windows": route.windows,
                "urgencyLevels": route.urgencyLevels,
            }
            for route in request.routes
        ]
        logger.info(
            "Classification call starting request_id=%s model=%s temperature=%s promptVersion=%s "
            "messageId=%s sender=%s recipients=%s subject=%s plainChars=%s htmlChars=%s routes=%s",
            request_id,
            self.model,
            self.temperature,
            request.promptVersion,
            email.messageId,
            email.sender,
            email.recipients,
            self._truncate(email.subject or ""),
            len(email.plain or ""),
            len(email.html or ""),
            routes,
        )

    def _truncate(self, value: str) -> str:
        if self.log_preview_chars == 0:
            return ""
        if len(value) <= self.log_preview_chars:
            return value
        return value[: self.log_preview_chars] + "...[truncated]"

