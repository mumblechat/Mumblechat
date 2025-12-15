package com.ramapay.app.ui.widget;

import android.view.View;

import com.ramapay.app.entity.tokens.Token;

import java.math.BigInteger;
import java.util.List;

public interface TokensAdapterCallback
{
    void onTokenClick(View view, Token token, List<BigInteger> tokenIds, boolean selected);
    void onLongTokenClick(View view, Token token, List<BigInteger> tokenIds);
    default void reloadTokens() { };
    default void onBuyToken() { }
    default void onAddToken() { }
    default void onSearchClicked() { };
    default void onSwitchClicked() { };
    default void onWCClicked() { };
    default boolean hasWCSession() { return false; };
}
