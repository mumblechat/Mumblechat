package com.ramapay.app.service;

import static com.ramapay.app.ui.HomeActivity.AW_MAGICLINK_DIRECT;

import android.content.Intent;
import android.text.TextUtils;

import com.ramapay.app.api.v1.entity.request.ApiV1Request;
import com.ramapay.app.entity.CryptoFunctions;
import com.ramapay.app.entity.DeepLinkRequest;
import com.ramapay.app.entity.DeepLinkType;
import com.ramapay.app.entity.EIP681Type;
import com.ramapay.app.entity.QRResult;
import com.ramapay.app.entity.attestation.ImportAttestation;
import com.ramapay.app.repository.EthereumNetworkRepository;
import com.ramapay.app.util.Utils;
import com.ramapay.token.entity.SalesOrderMalformed;
import com.ramapay.token.tools.ParseMagicLink;

public class DeepLinkService
{
    public static final String AW_APP = "https://aw.app/";
    public static final String WC_PREFIX = "wc?uri=";
    public static final String WC_COMMAND = "wc:";
    public static final String AW_PREFIX = "awallet://";
    public static final String RAMAPAY_PREFIX = "ramapay://";
    public static final String MUMBLECHAT_PREFIX = "mumblechat://";
    public static final String MUMBLECHAT_CONNECT_PREFIX = "mumblechat://connect";
    public static final String OPEN_URL_PREFIX = "openURL?q=";

    public static DeepLinkRequest parseIntent(String importData, Intent startIntent)
    {
        boolean isOpenURL = false;

        if (TextUtils.isEmpty(importData))
        {
            return checkIntents(startIntent);
        }
        
        // Handle MumbleChat P2P peer exchange links first
        if (importData.startsWith(MUMBLECHAT_CONNECT_PREFIX) || importData.startsWith(MUMBLECHAT_PREFIX))
        {
            return new DeepLinkRequest(DeepLinkType.MUMBLECHAT_PEER, importData);
        }

        if (importData.startsWith(RAMAPAY_PREFIX)) //strip RAMAPAY_PREFIX
        {
            importData = importData.substring(RAMAPAY_PREFIX.length());
        }
        else if (importData.startsWith(AW_PREFIX)) //strip AW_PREFIX
        {
            importData = importData.substring(AW_PREFIX.length());
        }

        if (importData.startsWith(OPEN_URL_PREFIX))
        {
            isOpenURL = true;
            importData = importData.substring(OPEN_URL_PREFIX.length());
        }

        importData = Utils.universalURLDecode(importData);

        if (checkSmartPass(importData))
        {
            return new DeepLinkRequest(DeepLinkType.SMARTPASS, importData);
        }

        if (importData.startsWith(AW_APP + WC_PREFIX) || importData.startsWith(WC_PREFIX))
        {
            int prefixIndex = importData.indexOf(WC_PREFIX) + WC_PREFIX.length();
            return new DeepLinkRequest(DeepLinkType.WALLETCONNECT, importData.substring(prefixIndex));
        }

        if (importData.startsWith(WC_COMMAND))
        {
            return new DeepLinkRequest(DeepLinkType.WALLETCONNECT, importData);
        }

        if (importData.startsWith(NotificationService.AWSTARTUP))
        {
            return new DeepLinkRequest(DeepLinkType.TOKEN_NOTIFICATION, importData.substring(NotificationService.AWSTARTUP.length()));
        }

        if (importData.startsWith("wc:"))
        {
            return new DeepLinkRequest(DeepLinkType.WALLETCONNECT, importData);
        }

        if (new ApiV1Request(importData).isValid())
        {
            return new DeepLinkRequest(DeepLinkType.WALLET_API_DEEPLINK, importData);
        }

        int directLinkIndex = importData.indexOf(AW_MAGICLINK_DIRECT);
        if (directLinkIndex > 0)
        {
            String link = importData.substring(directLinkIndex + AW_MAGICLINK_DIRECT.length());
            if (Utils.isValidUrl(link))
            {
                //get link
                return new DeepLinkRequest(DeepLinkType.URL_REDIRECT, link);
            }
        }

        if (isLegacyMagiclink(importData))
        {
            return new DeepLinkRequest(DeepLinkType.LEGACY_MAGICLINK, importData);
        }

        if (startIntent != null && importData.startsWith("content://") && startIntent.getData() != null
            && !TextUtils.isEmpty(startIntent.getData().getPath()))
        {
            return new DeepLinkRequest(DeepLinkType.IMPORT_SCRIPT, null);
        }

        //finally check if it's a plain openURL
        if (isOpenURL && Utils.isValidUrl(importData))
        {
            return new DeepLinkRequest(DeepLinkType.URL_REDIRECT, importData);
        }

        // finally see if there was a url in the intent (with non empty importData) or bail with invalid link
        return checkIntents(startIntent);
    }

    // Check possibilities where importData is empty
    private static DeepLinkRequest checkIntents(Intent startIntent)
    {
        String startIntentData = startIntent != null ? startIntent.getStringExtra("url") : null;
        if (startIntentData != null)
        {
            return new DeepLinkRequest(DeepLinkType.URL_REDIRECT, startIntentData);
        }
        else
        {
            return new DeepLinkRequest(DeepLinkType.INVALID_LINK, null);
        }
    }

    private static boolean isLegacyMagiclink(String importData)
    {
        try
        {
            ParseMagicLink parser = new ParseMagicLink(new CryptoFunctions(), EthereumNetworkRepository.extraChains());
            if (parser.parseUniversalLink(importData).chainId > 0)
            {
                return true;
            }
        }
        catch (SalesOrderMalformed e)
        {
            //
        }

        return false;
    }

    private static boolean checkSmartPass(String importData)
    {
        QRResult result = null;
        if (importData != null)
        {
            if (importData.startsWith(ImportAttestation.SMART_PASS_URL))
            {
                importData = importData.substring(ImportAttestation.SMART_PASS_URL.length()); //chop off leading URL
            }

            String taglessAttestation = Utils.parseEASAttestation(importData);
            if (taglessAttestation != null && taglessAttestation.length() > 0)
            {
                result = new QRResult(importData);
                result.type = EIP681Type.EAS_ATTESTATION;
                result.functionDetail = Utils.toAttestationJson(taglessAttestation);
            }
        }

        return result != null && !TextUtils.isEmpty(result.functionDetail);
    }
}
