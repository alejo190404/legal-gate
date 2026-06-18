package com.legalgate.mail.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

public record CloudMailinMessage(
        @NotNull @Valid Envelope envelope,
        Map<String, Object> headers,
        String plain,
        String html,
        @JsonProperty("reply_plain") String replyPlain,
        List<Attachment> attachments
) {
    public record Envelope(
            String to,
            List<String> recipients,
            String from,
            @JsonProperty("remote_ip") String remoteIp
    ) {
    }

    public record Attachment(
            @JsonProperty("file_name") String fileName,
            @JsonProperty("content_type") String contentType,
            Long size,
            String disposition,
            String url
    ) {
    }
}
