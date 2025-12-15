package com.ramapay.app.ui.widget.entity;

import android.content.Intent;

import androidx.activity.result.ActivityResultLauncher;

import com.ramapay.app.entity.SignAuthenticationCallback;
import com.ramapay.app.entity.WalletType;
import com.ramapay.app.entity.tokens.Token;
import com.ramapay.app.service.GasService;
import com.ramapay.app.web3.entity.Web3Transaction;
import com.ramapay.hardware.SignatureFromKey;
import com.ramapay.token.entity.Signable;

import java.math.BigInteger;

/**
 * Created by JB on 27/11/2020.
 */
public interface ActionSheetCallback
{
    void getAuthorisation(SignAuthenticationCallback callback);

    void sendTransaction(Web3Transaction tx);

    //For Hardware wallet
    void completeSendTransaction(Web3Transaction tx, SignatureFromKey signature); //return from hardware signing

    default void completeSignTransaction(Web3Transaction tx, SignatureFromKey signature) //return from hardware signing - sign tx only
    {

    }

    void dismissed(String txHash, long callbackId, boolean actionCompleted);

    void notifyConfirm(String mode);

    ActivityResultLauncher<Intent> gasSelectLauncher();

    default void signTransaction(Web3Transaction tx)
    {
    } // only WalletConnect uses this so far

    default void buttonClick(long callbackId, Token baseToken)
    {
    }

    default void notifyWalletConnectApproval(long chainId)
    {
    }

    default void denyWalletConnect()
    {
    }

    default void openChainSelection()
    {
    }

    default void signingComplete(SignatureFromKey signature, Signable message)
    {
    }

    default void signingFailed(Throwable error, Signable message)
    {
    }

    WalletType getWalletType();

    default BigInteger getTokenId()
    {
        return BigInteger.ZERO;
    }

    GasService getGasService();

    /**
     * Check if transaction-level authentication is required
     * @return true if user must authenticate before each transaction
     */
    default boolean requiresTransactionAuth()
    {
        return false;
    }

    /**
     * Request transaction-level authentication (biometric/password) before sending
     * @param callback Called with result of authentication
     */
    default void requestTransactionAuth(TransactionAuthCallback callback)
    {
        // Default implementation: no additional auth required
        callback.onTransactionAuthResult(true);
    }

    /**
     * Callback for transaction-level authentication
     */
    interface TransactionAuthCallback
    {
        void onTransactionAuthResult(boolean authenticated);
    }
}
