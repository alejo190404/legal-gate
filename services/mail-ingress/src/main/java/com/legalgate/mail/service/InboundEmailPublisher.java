package com.legalgate.mail.service;

import com.legalgate.mail.config.MailIngressProperties;
import com.legalgate.mail.model.InboundEmailReceived;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
public class InboundEmailPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final MailIngressProperties properties;

    public InboundEmailPublisher(RabbitTemplate rabbitTemplate, MailIngressProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    public void publish(InboundEmailReceived event) throws AmqpException {
        rabbitTemplate.convertAndSend(properties.exchange(), properties.routingKey(), event);
    }
}
