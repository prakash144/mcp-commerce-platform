package com.commerce.order.exception;

import com.commerce.order.client.PermanentPaymentException;
import com.commerce.order.client.RecoverablePaymentException;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.web.bind.annotation.ControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@ControllerAdvice
@Slf4j
public class OrderGraphQLExceptionHandler {

    @GraphQlExceptionHandler
    public GraphQLError handleOrderNotFound(OrderNotFoundException ex) {
        return GraphqlErrorBuilder.newError()
                .message(ex.getMessage())
                .errorType(ErrorType.NOT_FOUND)
                .extensions(Map.of("code", "ORDER_NOT_FOUND"))
                .build();
    }

    @GraphQlExceptionHandler
    public GraphQLError handleValidation(OrderValidationException ex) {
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("code", ex.getCode());
        extensions.putAll(ex.getDetails());
        return GraphqlErrorBuilder.newError()
                .message(ex.getMessage())
                .errorType(ErrorType.BAD_REQUEST)
                .extensions(extensions)
                .build();
    }

    @GraphQlExceptionHandler
    public GraphQLError handlePaymentUnavailable(RecoverablePaymentException ex) {
        return paymentUnavailable(ex);
    }

    @GraphQlExceptionHandler
    public GraphQLError handlePaymentUnavailable(CallNotPermittedException ex) {
        return paymentUnavailable(ex);
    }

    private GraphQLError paymentUnavailable(Exception ex) {
        log.warn("Payment unavailable: {}", ex.getMessage());
        return GraphqlErrorBuilder.newError()
                .message("Payments are temporarily unavailable. Please try again.")
                .errorType(ErrorType.INTERNAL_ERROR)
                .extensions(Map.of("code", "PAYMENT_UNAVAILABLE"))
                .build();
    }

    @GraphQlExceptionHandler
    public GraphQLError handlePaymentRejected(PermanentPaymentException ex) {
        return GraphqlErrorBuilder.newError()
                .message(ex.getMessage())
                .errorType(ErrorType.BAD_REQUEST)
                .extensions(Map.of("code", "PAYMENT_REJECTED"))
                .build();
    }

    @GraphQlExceptionHandler
    public GraphQLError handleGeneric(Exception ex) {
        log.error("Unhandled GraphQL exception: {}", ex.getMessage(), ex);
        return GraphqlErrorBuilder.newError()
                .message("Internal server error")
                .errorType(ErrorType.INTERNAL_ERROR)
                .extensions(Map.of("code", "INTERNAL_ERROR"))
                .build();
    }
}