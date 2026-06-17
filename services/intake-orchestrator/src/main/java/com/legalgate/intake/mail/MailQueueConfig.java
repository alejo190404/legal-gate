package com.legalgate.intake.mail;

import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "legalgate.intake.mail.enabled", havingValue = "true")
@EnableConfigurationProperties(MailQueueProperties.class)
class MailQueueConfig {

    @Bean
    DirectExchange mailExchange(MailQueueProperties properties) {
        return new DirectExchange(properties.exchange(), true, false);
    }

    @Bean
    Queue incomingMailQueue(MailQueueProperties properties) {
        return new Queue(properties.incomingQueue(), true, false, false, Map.of(
                "x-dead-letter-exchange", properties.exchange(),
                "x-dead-letter-routing-key", properties.deadLetterRoutingKey()
        ));
    }

    @Bean
    Queue incomingMailDeadLetterQueue(MailQueueProperties properties) {
        return new Queue(properties.deadLetterQueue(), true);
    }

    @Bean
    Binding incomingMailBinding(MailQueueProperties properties, Queue incomingMailQueue, DirectExchange mailExchange) {
        return BindingBuilder.bind(incomingMailQueue).to(mailExchange).with(properties.routingKey());
    }

    @Bean
    Binding incomingMailDeadLetterBinding(
            MailQueueProperties properties,
            Queue incomingMailDeadLetterQueue,
            DirectExchange mailExchange
    ) {
        return BindingBuilder.bind(incomingMailDeadLetterQueue).to(mailExchange).with(properties.deadLetterRoutingKey());
    }

    @Bean
    MessageConverter mailMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter mailMessageConverter
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(mailMessageConverter);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
