package com.ramapay.app.ui.widget;

import java.io.Serializable;

import com.ramapay.app.entity.DApp;

public interface OnDappClickListener extends Serializable {
    void onDappClick(DApp dapp);
}
