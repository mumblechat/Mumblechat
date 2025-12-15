package com.ramapay.app.ui.widget;

import com.ramapay.app.entity.tokens.Token;

public interface OnTokenManageClickListener
{
    void onTokenClick(Token token, int position, boolean isChecked);
}
