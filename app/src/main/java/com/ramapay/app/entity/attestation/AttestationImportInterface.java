package com.ramapay.app.entity.attestation;

import com.ramapay.app.entity.tokens.TokenCardMeta;

public interface AttestationImportInterface
{
    void attestationImported(TokenCardMeta newToken);
    void importError(String error);
    void smartPassValidation(SmartPassReturn validation);
}
