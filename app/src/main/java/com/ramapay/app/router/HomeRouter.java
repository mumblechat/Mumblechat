package com.ramapay.app.router;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import com.ramapay.app.C;
import com.ramapay.app.ui.HomeActivity;

public class HomeRouter {
    public static final String NEW_WALLET_CREATED = "NEW_WALLET_CREATED";
    
    public void open(Context context, boolean isClearStack) {
        open(context, isClearStack, false);
    }
    
    public void open(Context context, boolean isClearStack, boolean newWalletCreated) {
        Intent intent = new Intent(context, HomeActivity.class);
        intent.putExtra(C.FROM_HOME_ROUTER, C.FROM_HOME_ROUTER); //HomeRouter should restart the app at the wallet
        if (newWalletCreated) {
            intent.putExtra(NEW_WALLET_CREATED, true);
        }
        if (isClearStack) {
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        }
        context.startActivity(intent);
        
        // Apply instant transition for faster feel
        if (context instanceof Activity) {
            ((Activity) context).overridePendingTransition(0, 0);
        }
    }
}
