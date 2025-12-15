package com.ramapay.app.repository;

import com.ramapay.app.entity.ContractType;
import com.ramapay.app.entity.tokendata.TokenGroup;
import com.ramapay.token.entity.ContractAddress;

public interface TokensMappingRepositoryType
{
    TokenGroup getTokenGroup(long chainId, String address, ContractType type);

    ContractAddress getBaseToken(long chainId, String address);
}
