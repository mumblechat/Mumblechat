package com.ramapay.app.entity;

import com.ramapay.token.entity.TokenScriptResult;

import java.util.List;

public interface TSAttrCallback
{
    void showTSAttributes(List<TokenScriptResult.Attribute> attrs, boolean updateRequired);
}
