package com.commerce.order.config;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Locale;

@Configuration
public class OrderScalars {

    @Bean
    GraphQLScalarType BigDecimal() {
        return GraphQLScalarType.newScalar()
                .name("BigDecimal")
                .description("An arbitrary-precision decimal (money values)")
                .coercing(new BigDecimalCoercing())
                .build();
    }

    @Bean
    GraphQLScalarType DateTime() {
        return GraphQLScalarType.newScalar()
                .name("DateTime")
                .description("An ISO-8601 date-time, e.g. 2026-09-06T10:15:30Z")
                .coercing(new InstantCoercing())
                .build();
    }

    static class BigDecimalCoercing implements Coercing<BigDecimal, BigDecimal> {

        @Override
        public BigDecimal serialize(Object dataFetcherResult, GraphQLContext graphQLContext, Locale locale) {
            if (dataFetcherResult instanceof BigDecimal bd) {
                return bd;
            }
            if (dataFetcherResult instanceof Number || dataFetcherResult instanceof String) {
                return new BigDecimal(String.valueOf(dataFetcherResult));
            }
            throw new CoercingSerializeException(
                    "Expected BigDecimal but was " + dataFetcherResult.getClass());
        }

        @Override
        public BigDecimal parseValue(Object input, GraphQLContext graphQLContext, Locale locale) {
            if (input instanceof BigDecimal bd) {
                return bd;
            }
            if (input instanceof Number || input instanceof String) {
                return new BigDecimal(String.valueOf(input));
            }
            throw new CoercingParseValueException("Expected numeric value but was " + input);
        }

        @Override
        public BigDecimal parseLiteral(Value<?> input, CoercedVariables variables,
                                       GraphQLContext graphQLContext, Locale locale) {
            if (input instanceof IntValue iv) {
                return new BigDecimal(iv.getValue());
            }
            if (input instanceof FloatValue fv) {
                return fv.getValue();
            }
            if (input instanceof StringValue sv) {
                return new BigDecimal(sv.getValue());
            }
            throw new CoercingParseLiteralException("Expected numeric literal but was " + input.getClass());
        }
    }

    static class InstantCoercing implements Coercing<Instant, String> {

        @Override
        public String serialize(Object dataFetcherResult, GraphQLContext graphQLContext, Locale locale) {
            if (dataFetcherResult instanceof Instant instant) {
                return instant.toString();
            }
            throw new CoercingSerializeException(
                    "Expected java.time.Instant but was " + dataFetcherResult.getClass());
        }

        @Override
        public Instant parseValue(Object input, GraphQLContext graphQLContext, Locale locale) {
            if (input instanceof String s) {
                return parse(s);
            }
            throw new CoercingParseValueException("Expected ISO-8601 string but was " + input);
        }

        @Override
        public Instant parseLiteral(Value<?> input, CoercedVariables variables,
                                    GraphQLContext graphQLContext, Locale locale) {
            if (input instanceof StringValue sv) {
                return parse(sv.getValue());
            }
            throw new CoercingParseLiteralException("Expected StringValue but was " + input.getClass());
        }

        private Instant parse(String value) {
            try {
                return Instant.parse(value);
            } catch (Exception e) {
                return OffsetDateTime.parse(value).toInstant();
            }
        }
    }
}