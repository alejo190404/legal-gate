package com.legalgate.intake.service;

/**
 * One email body as the renderer produced it: the plaintext part and the HTML card (ADR-0009).
 */
public record RenderedEmail(String text, String html) {
}
