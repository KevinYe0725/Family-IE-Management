package com.familyfinance.shared;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiEnvelope<T>(T data, ApiError error) {

    public static <T> ApiEnvelope<T> data(T data) {
        return new ApiEnvelope<>(data, null);
    }

    public static ApiEnvelope<Void> error(ApiError error) {
        return new ApiEnvelope<>(null, error);
    }
}
