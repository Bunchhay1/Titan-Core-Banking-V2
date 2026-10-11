package com.titan.titancorebanking.graphql;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Component
public class TransactionEventPublisher {

    private final Sinks.Many<TransactionEventDTO> sink =
            Sinks.many().multicast().onBackpressureBuffer(256, false);

    public void publish(TransactionEventDTO event) {
        sink.emitNext(event, Sinks.EmitFailureHandler.FAIL_FAST);
    }

    public Flux<TransactionEventDTO> getStream() {
        return sink.asFlux();
    }
}