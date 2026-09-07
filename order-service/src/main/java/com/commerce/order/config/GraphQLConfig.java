package com.commerce.order.config;

import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.schema.GraphQLScalarType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

@Configuration
public class GraphQLConfig {

    @Bean
    MaxQueryDepthInstrumentation maxQueryDepthInstrumentation() {
        return new MaxQueryDepthInstrumentation(10);
    }

    @Bean
    MaxQueryComplexityInstrumentation maxQueryComplexityInstrumentation() {
        return new MaxQueryComplexityInstrumentation(50);
    }

    @Bean
    RuntimeWiringConfigurer orderScalarWiringConfigurer(@Qualifier("BigDecimal") GraphQLScalarType bigDecimal,
                                                        @Qualifier("DateTime") GraphQLScalarType dateTime) {
        return builder -> builder.scalar(bigDecimal).scalar(dateTime);
    }
}
