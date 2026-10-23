package com.ladder.transcode;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TranscodeQueueConfig {
    public static final String EXCHANGE = "ladder.transcode";
    public static final String ROUTING_KEY = "transcode.job";
    public static final String QUEUE = "transcode.jobs";
    public static final String DEAD_LETTER_QUEUE = "transcode.jobs.dlq";

    @Bean
    DirectExchange transcodeExchange() {
        return new DirectExchange(EXCHANGE);
    }

    // Dead-lettering is declared now, before the worker exists, because queue
    // arguments can't be changed on an existing queue: adding them later means
    // deleting the queue. "" is the default exchange, which routes by queue name.
    @Bean
    Queue transcodeQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(DEAD_LETTER_QUEUE)
                .build();
    }

    @Bean
    Queue transcodeDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding transcodeBinding(Queue transcodeQueue, DirectExchange transcodeExchange) {
        return BindingBuilder.bind(transcodeQueue).to(transcodeExchange).with(ROUTING_KEY);
    }

    // Boot plugs this into both RabbitTemplate and @RabbitListener. Without it,
    // objects are sent with Java serialization: unreadable outside Java, and
    // deserializing untrusted bytes is a known remote-code-execution risk.
    @Bean
    MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
