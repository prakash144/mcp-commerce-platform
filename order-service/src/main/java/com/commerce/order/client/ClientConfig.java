package com.commerce.order.client;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class ClientConfig {

    @Bean
    RestClient productRestClient(@Value("${commerce.product-service.base-url}") String baseUrl,
                                  CorrelationInterceptor correlationInterceptor) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl(baseUrl)
                .requestInterceptor(correlationInterceptor)
                .build();
    }

    @Bean(destroyMethod = "shutdown")
    ManagedChannel paymentChannel(@Value("${commerce.payment-service.target:localhost:50051}") String target) {
        return ManagedChannelBuilder.forTarget(target).usePlaintext().build();
    }
}
