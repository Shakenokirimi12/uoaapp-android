package com.shakenokirimi12.uoa_app.services;

public interface ServiceCallback<T> {
    void onSuccess(T result);
    void onError(String message);
}
