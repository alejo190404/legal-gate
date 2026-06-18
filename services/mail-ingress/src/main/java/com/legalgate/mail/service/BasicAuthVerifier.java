package com.legalgate.mail.service;

import com.legalgate.mail.config.MailIngressProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class BasicAuthVerifier {

    private final MailIngressProperties properties;

    BasicAuthVerifier(MailIngressProperties properties) {
        this.properties = properties;
    }

    public boolean isValid(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Basic ")) {
            return false;
        }
        String encodedCredentials = authorizationHeader.substring("Basic ".length()).trim();
        String expectedCredentials = properties.basicAuth().username() + ":" + properties.basicAuth().password();
        byte[] expected = Base64.getEncoder().encode(expectedCredentials.getBytes(StandardCharsets.UTF_8));
        byte[] actual = encodedCredentials.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }
}
