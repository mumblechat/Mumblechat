package com.ramapay.app.web3;

import com.ramapay.token.entity.EthereumMessage;

public interface OnSignMessageListener {
    void onSignMessage(EthereumMessage message);
}
