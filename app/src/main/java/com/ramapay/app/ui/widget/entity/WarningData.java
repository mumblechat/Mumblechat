package com.ramapay.app.ui.widget.entity;

import com.ramapay.app.entity.BackupTokenCallback;
import com.ramapay.app.entity.Wallet;

/**
 * Created by James on 18/07/2019.
 * Stormbird in Sydney
 */
public class WarningData
{
    public String title;
    public String detail;
    public String buttonText;
    public Wallet wallet;
    public int colour;
    public int buttonColour;
    public final BackupTokenCallback callback;

    public WarningData(BackupTokenCallback tCallback)
    {
        callback = tCallback;
    }
}
