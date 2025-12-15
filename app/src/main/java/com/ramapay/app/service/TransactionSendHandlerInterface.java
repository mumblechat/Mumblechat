package com.ramapay.app.service;

import com.ramapay.app.entity.TransactionReturn;
import com.ramapay.app.web3.entity.Web3Transaction;
import com.ramapay.hardware.SignatureFromKey;

/**
 * Created by JB on 2/02/2023.
 */
public interface TransactionSendHandlerInterface
{
    void transactionFinalised(TransactionReturn txData);

    void transactionError(TransactionReturn txError);

    default void transactionSigned(SignatureFromKey sigData, Web3Transaction w3Tx)
    {
    } //Not always required, only WalletConnect
}
