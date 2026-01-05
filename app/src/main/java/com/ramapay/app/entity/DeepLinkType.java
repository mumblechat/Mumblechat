package com.ramapay.app.entity;

public enum DeepLinkType
{
    WALLETCONNECT,
    SMARTPASS,
    URL_REDIRECT,
    TOKEN_NOTIFICATION,
    WALLET_API_DEEPLINK,
    LEGACY_MAGICLINK, //ERC875 token import
    IMPORT_SCRIPT,
    MUMBLECHAT_PEER,  // MumbleChat P2P peer exchange deep link
    INVALID_LINK

}
