from __future__ import annotations

import json
import os
from typing import Any

from google import genai
from google.genai import types
from pydantic import ValidationError

from .models import ConsultationClassificationRequest, ConsultationClassificationResponse


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
        self.client = genai.Client(api_key=self.api_key) if self.api_key else None

    def classify(
        self,
        request: ConsultationClassificationRequest,
    ) -> ConsultationClassificationResponse:
        if self.client is None:
            raise GeminiClassifierError(
                "gemini_unavailable",
                "GEMINI_API_KEY is not configured for consultation-classifier.",
            )

        prompt = self._prompt_for(request)
        try:
            result = self._generate_structured_content(prompt)
        except Exception as exc:
            raise GeminiClassifierError("gemini_unavailable", "Gemini request failed.") from exc

        raw = self._extract_text(result)
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise GeminiClassifierError(
                "gemini_invalid_response",
                "Gemini returned non-JSON output.",
                raw,
            ) from exc

        try:
            return ConsultationClassificationResponse.model_validate(payload)
        except ValidationError as exc:
            raise GeminiClassifierError(
                "gemini_invalid_response",
                "Gemini JSON did not match the expected schema.",
                payload,
            ) from exc

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
