package com.ramapay.shadows;

import android.content.Context;

import com.ramapay.app.entity.AnalyticsProperties;
import com.ramapay.app.service.AnalyticsServiceType;
import com.ramapay.app.service.KeyService;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@Implements(KeyService.class)
public class ShadowKeyService
{
    @Implementation
    public void __constructor__(Context ctx, AnalyticsServiceType<AnalyticsProperties> analyticsService) {

    }
}
