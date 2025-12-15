package com.ramapay.app.walletconnect.entity;

import com.ramapay.token.entity.EthereumMessage;
import com.ramapay.token.entity.SignMessageType;
import com.ramapay.token.entity.Signable;

public class SignRequest extends BaseRequest
{
    public SignRequest(String params)
    {
        super(params, WCEthereumSignMessage.WCSignType.MESSAGE);
    }

    @Override
    public Signable getSignable()
    {
        return new EthereumMessage(getMessage(), "", 0, SignMessageType.SIGN_MESSAGE);
    }

    @Override
    public Signable getSignable(long callbackId, String origin)
    {
        return new EthereumMessage(getMessage(), origin, callbackId, SignMessageType.SIGN_MESSAGE);
    }

    @Override
    public String getWalletAddress()
    {
        return params.get(0);
    }
}
