package com.legalgate.mail.model;

public record InboundEmailIngestionResult(InboundEmailReceived event, String status) {
}
