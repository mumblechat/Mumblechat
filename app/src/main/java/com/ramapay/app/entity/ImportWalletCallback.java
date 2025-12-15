package com.ramapay.app.entity;
import com.ramapay.app.entity.cryptokeys.KeyEncodingType;
import com.ramapay.app.service.KeyService;

public interface ImportWalletCallback
{
    void walletValidated(String address, KeyEncodingType type, KeyService.AuthenticationLevel level);
}
