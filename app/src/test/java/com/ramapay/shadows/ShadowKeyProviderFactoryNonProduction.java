package com.ramapay.shadows;


import com.ramapay.app.di.mock.KeyProviderMockNonProductionImpl;
import com.ramapay.app.repository.KeyProvider;
import com.ramapay.app.repository.KeyProviderFactory;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@Implements(KeyProviderFactory.class)
public class ShadowKeyProviderFactoryNonProduction
{
    @Implementation
    public static KeyProvider get() {
        return new KeyProviderMockNonProductionImpl();
    }
}
