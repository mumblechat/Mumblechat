package com.ramapay.app.viewmodel;

import com.ramapay.app.service.KeyService;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

@HiltViewModel
public class ScammerWarningViewModel extends BaseViewModel {
    
    private final KeyService keyService;

    @Inject
    public ScammerWarningViewModel(KeyService keyService) {
        this.keyService = keyService;
    }

    public boolean hasKey(String address) {
        return keyService.hasKeystore(address);
    }
}
