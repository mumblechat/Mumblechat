package com.ramapay.app.router;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

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
        
        // Use ActivityOptions to apply quick cross-fade animation on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && context instanceof Activity) {
            // Android 14+ - use makeCustomAnimation with no splash
            ActivityOptions options = ActivityOptions.makeCustomAnimation(context, 
                android.R.anim.fade_in, android.R.anim.fade_out);
            context.startActivity(intent, options.toBundle());
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && context instanceof Activity) {
            // Android 13 - use custom animation 
            ActivityOptions options = ActivityOptions.makeCustomAnimation(context, 
                android.R.anim.fade_in, android.R.anim.fade_out);
            context.startActivity(intent, options.toBundle());
        } else {
            context.startActivity(intent);
            // Apply fade transition for smooth visual
            if (context instanceof Activity) {
                ((Activity) context).overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            }
        }
    }
}
