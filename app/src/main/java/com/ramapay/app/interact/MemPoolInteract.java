package com.ramapay.app.interact;

import com.ramapay.app.repository.TokenRepositoryType;

import io.reactivex.Observable;

import com.ramapay.app.entity.TransferFromEventResponse;

/**
 * Created by James on 1/02/2018.
 */

public class MemPoolInteract
{
    private final TokenRepositoryType tokenRepository;

    public MemPoolInteract(TokenRepositoryType tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    //create an observable
    public Observable<TransferFromEventResponse> burnListener(String contractAddress) {
        return tokenRepository.burnListenerObservable(contractAddress);
    }
}
