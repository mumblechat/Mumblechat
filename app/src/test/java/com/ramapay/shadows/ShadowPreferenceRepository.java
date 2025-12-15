package com.ramapay.shadows;

import android.content.Context;

import com.ramapay.app.repository.SharedPreferenceRepository;
import com.ramapay.app.service.AnalyticsService;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@Implements(SharedPreferenceRepository.class)
public class ShadowPreferenceRepository
{
    @Implementation
    public void __constructor__(Context context) {
    }
}
