package com.ramapay.app.repository;

import com.ramapay.app.entity.OnRampContract;
import com.ramapay.app.entity.tokens.Token;

public interface OnRampRepositoryType {
    String getUri(String address, Token token);

    OnRampContract getContract(Token token);
}
