package com.titan.promotions.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import reactor.core.publisher.Mono;

@Configuration
@EnableWebSocket
public class GraphQLWebSocketConfig implements WebGraphQlInterceptor {
    
    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        return chain.next(request);
    }
}
