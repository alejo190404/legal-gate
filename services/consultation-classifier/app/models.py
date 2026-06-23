from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field, field_validator


class InboundEmail(BaseModel):
    subject: str | None = None
    plain: str | None = None
    html: str | None = None
    sender: str | None = None
    recipients: list[str] = Field(default_factory=list)
    messageId: str | None = None


class TenantRoute(BaseModel):
    routeIndex: int = Field(ge=0)
    name: str = Field(min_length=1)
    description: str | None = None
    destinationEmail: str | None = None
    keywords: list[str] = Field(default_factory=list)
    windows: list[str] = Field(default_factory=list)
    urgencyLevels: list[str] = Field(min_length=1)

    @field_validator("name")
    @classmethod
    def trim_name(cls, value: str) -> str:
        trimmed = value.strip()
        if not trimmed:
            raise ValueError("route name is required")
        return trimmed

    @field_validator("description")
    @classmethod
    def trim_description(cls, value: str | None) -> str | None:
        if value is None:
            return None
        trimmed = value.strip()
        return trimmed or None

    @field_validator("urgencyLevels")
    @classmethod
    def validate_urgency_levels(cls, values: list[str]) -> list[str]:
        trimmed: list[str] = []
        seen: set[str] = set()
        for value in values:
            item = value.strip()
            if not item:
                raise ValueError("route urgency levels cannot be blank")
            if item in seen:
                raise ValueError("route urgency levels must be unique")
            trimmed.append(item)
            seen.add(item)
        return trimmed


class ConsultationClassificationRequest(BaseModel):
    email: InboundEmail
    routes: list[TenantRoute] = Field(min_length=1)
    systemPrompt: str
    promptVersion: str | None = None

    @field_validator("systemPrompt")
    @classmethod
    def trim_system_prompt(cls, value: str) -> str:
        trimmed = value.strip()
        if not trimmed:
            raise ValueError("systemPrompt is required")
        return trimmed


class ConsultationClassificationResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    routeIndex: int = Field(ge=0)
    consultationType: str = Field(min_length=1)
    urgency: str = Field(min_length=1)
    concept: str = Field(min_length=1)
    summary: str = Field(min_length=1)
    clientName: str = Field(min_length=1)
    explanation: str = Field(min_length=1)
    confidence: float = Field(ge=0, le=1)


class ErrorDetail(BaseModel):
    error: str
    message: str
    raw: Any | None = None
