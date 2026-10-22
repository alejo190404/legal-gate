package com.legalgate.intake.service;

/**
 * One email body as the renderer produced it: the plaintext part and the HTML card. {@code html}
 * is null for a type that has no card yet; the plaintext part is always present (ADR-0009).
 */
public record RenderedEmail(String text, String html) {
}
