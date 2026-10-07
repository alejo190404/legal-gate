package com.legalgate.intake.classifier;

/** A Client's email as mail-ingress delivered it, Email Boilerplate and Quoted History already stripped. */
public record ClientMailIntentRequest(String subject, String body) {
}
