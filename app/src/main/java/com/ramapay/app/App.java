package com.ramapay.app;

import static androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO;
import static androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES;

import android.app.Activity;
import android.app.Application;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;

import com.ramapay.app.service.AppSecurityManager;
import com.ramapay.app.util.TimberInit;
import com.ramapay.app.walletconnect.AWWalletConnectClient;

import java.util.EmptyStackException;
import java.util.Stack;

import javax.inject.Inject;

import dagger.hilt.android.HiltAndroidApp;
import io.reactivex.plugins.RxJavaPlugins;
import io.realm.Realm;
import timber.log.Timber;

@HiltAndroidApp
public class App extends Application
{
    @Inject
    AWWalletConnectClient awWalletConnectClient;
    
    @Inject
    AppSecurityManager appSecurityManager;

    private static App mInstance;
    private final Stack<Activity> activityStack = new Stack<>();
    private ScreenOffReceiver screenOffReceiver;

    public static App getInstance()
    {
        return mInstance;
    }

    public Activity getTopActivity()
    {
        try
        {
            return activityStack.peek();
        }
        catch (EmptyStackException e)
        {
            //
            return null;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onCreate()
    {
        super.onCreate();
        mInstance = this;
        Realm.init(this);
        TimberInit.configTimber();

        int defaultTheme = PreferenceManager.getDefaultSharedPreferences(this)
                .getInt("theme", C.THEME_DARK);

        if (defaultTheme == C.THEME_LIGHT)
        {
            AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_NO);
        }
        else if (defaultTheme == C.THEME_DARK)
        {
            AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_YES);
        }
        else
        {
            UiModeManager uiModeManager = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
            int mode = uiModeManager.getNightMode();
            if (mode == UiModeManager.MODE_NIGHT_YES)
            {
                AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_YES);
            }
            else if (mode == UiModeManager.MODE_NIGHT_NO)
            {
                AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_NO);
            }
        }

        RxJavaPlugins.setErrorHandler(Timber::e);

        try
        {
            awWalletConnectClient.init(this);
        }
        catch (Exception e)
        {
            Timber.tag("WalletConnect").e(e);
        }
        
        // Register screen off receiver to lock app when screen turns off
        registerScreenOffReceiver();

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks()
        {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState)
            {
            }

            @Override
            public void onActivityDestroyed(Activity activity)
            {
            }

            @Override
            public void onActivityStarted(Activity activity)
            {
            }

            @Override
            public void onActivityResumed(Activity activity)
            {
                activityStack.push(activity);
            }

            @Override
            public void onActivityPaused(Activity activity)
            {
                pop();
            }

            @Override
            public void onActivityStopped(Activity activity)
            {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState)
            {
            }
        });
    }

    @Override
    public void onTrimMemory(int level)
    {
        super.onTrimMemory(level);
        if (awWalletConnectClient != null)
        {
            awWalletConnectClient.shutdown();
        }
    }

    @Override
    public void onTerminate()
    {
        super.onTerminate();
        activityStack.clear();
        if (awWalletConnectClient != null)
        {
            awWalletConnectClient.shutdown();
        }
        unregisterScreenOffReceiver();
    }

    private void pop()
    {
        activityStack.pop();
    }
    
    /**
     * Register broadcast receiver to listen for screen off events
     */
    private void registerScreenOffReceiver()
    {
        if (screenOffReceiver == null)
        {
            screenOffReceiver = new ScreenOffReceiver();
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            registerReceiver(screenOffReceiver, filter);
        }
    }
    
    /**
     * Unregister screen off receiver
     */
    private void unregisterScreenOffReceiver()
    {
        if (screenOffReceiver != null)
        {
            try
            {
                unregisterReceiver(screenOffReceiver);
            }
            catch (Exception e)
            {
                Timber.e(e, "Error unregistering screen off receiver");
            }
            screenOffReceiver = null;
        }
    }
    
    /**
     * BroadcastReceiver to detect when screen is turned off
     * Locks the app when screen turns off for security
     */
    private class ScreenOffReceiver extends BroadcastReceiver
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction()))
            {
                Timber.d("Screen turned off - locking app");
                if (appSecurityManager != null)
                {
                    appSecurityManager.onScreenOff();
                }
            }
        }
    }
}
