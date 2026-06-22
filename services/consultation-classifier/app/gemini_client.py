from __future__ import annotations

import json
import logging
import os
import uuid
from typing import Any

from google import genai
from google.genai import types
from pydantic import ValidationError

from .models import ConsultationClassificationRequest, ConsultationClassificationResponse

logger = logging.getLogger("legalgate.consultation_classifier")


class GeminiClassifierError(RuntimeError):
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
        self.log_payloads = os.getenv("CLASSIFIER_LOG_PAYLOADS", "false").lower() == "true"
        self.log_preview_chars = self._int_from_env("CLASSIFIER_LOG_PREVIEW_CHARS", 500)
        self.client = genai.Client(api_key=self.api_key) if self.api_key else None

    def classify(
        self,
        request: ConsultationClassificationRequest,
    ) -> ConsultationClassificationResponse:
        classification_request_id = str(uuid.uuid4())
        self._log_request_start(classification_request_id, request)
        if self.client is None:
            logger.warning(
                "Gemini classification skipped request_id=%s reason=missing_api_key",
                classification_request_id,
            )
            raise GeminiClassifierError(
                "gemini_unavailable",
                "GEMINI_API_KEY is not configured for consultation-classifier.",
            )

        prompt = self._prompt_for(request)
        if self.log_payloads:
            logger.info(
                "Gemini classification prompt request_id=%s prompt=%s",
                classification_request_id,
                self._truncate(prompt),
            )
        try:
            result = self._generate_structured_content(prompt)
        except Exception as exc:
            logger.exception(
                "Gemini classification call failed request_id=%s model=%s",
                classification_request_id,
                self.model,
            )
            raise GeminiClassifierError("gemini_unavailable", "Gemini request failed.") from exc

        raw = self._extract_text(result)
        if self.log_payloads:
            logger.info(
                "Gemini classification raw response request_id=%s response=%s",
                classification_request_id,
                self._truncate(raw),
            )
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError as exc:
            logger.warning(
                "Gemini classification returned non-json request_id=%s model=%s response_preview=%s",
                classification_request_id,
                self.model,
                self._truncate(raw),
            )
            raise GeminiClassifierError(
                "gemini_invalid_response",
                "Gemini returned non-JSON output.",
                raw,
            ) from exc

        try:
            response = ConsultationClassificationResponse.model_validate(payload)
        except ValidationError as exc:
            logger.warning(
                "Gemini classification schema validation failed request_id=%s model=%s payload=%s",
                classification_request_id,
                self.model,
                self._truncate(json.dumps(payload, ensure_ascii=False)),
            )
            raise GeminiClassifierError(
                "gemini_invalid_response",
                "Gemini JSON did not match the expected schema.",
                payload,
            ) from exc
        logger.info(
            "Gemini classification succeeded request_id=%s model=%s routeIndex=%s urgency=%s confidence=%s concept=%s",
            classification_request_id,
            self.model,
            response.routeIndex,
            response.urgency,
            response.confidence,
            response.concept,
        )
        return response

    def _prompt_for(self, request: ConsultationClassificationRequest) -> str:
        return "\n\n".join(
            [
                request.systemPrompt,
                "Return only valid JSON matching the provided schema.",
                "Tenant urgency levels, ordered low to high:",
                json.dumps(request.urgencyLevels, ensure_ascii=False),
                "Tenant routes:",
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

    def _generate_structured_content(self, prompt: str) -> Any:
        schema = ConsultationClassificationResponse.model_json_schema()
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
                "destinationEmail": route.destinationEmail,
                "keywords": route.keywords,
                "windows": route.windows,
            }
            for route in request.routes
        ]
        logger.info(
            "Gemini classification call starting request_id=%s model=%s temperature=%s promptVersion=%s "
            "messageId=%s sender=%s recipients=%s subject=%s plainChars=%s htmlChars=%s urgencyLevels=%s routes=%s",
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
            request.urgencyLevels,
            routes,
        )

    def _truncate(self, value: str) -> str:
        if self.log_preview_chars == 0:
            return ""
        if len(value) <= self.log_preview_chars:
            return value
        return value[: self.log_preview_chars] + "...[truncated]"
