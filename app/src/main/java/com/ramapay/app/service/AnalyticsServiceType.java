package com.ramapay.app.service;

import com.ramapay.app.entity.ServiceErrorException;

public interface AnalyticsServiceType<T>
{
    void increment(String property);

    void track(String eventName);

    void track(String eventName, T event);

    void flush();

    void identify(String uuid);

    void recordException(ServiceErrorException e);
}