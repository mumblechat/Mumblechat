package com.ramapay.app.service;

import static android.os.VibrationEffect.DEFAULT_AMPLITUDE;
import static com.ramapay.app.entity.Operation.CREATE_KEYSTORE_KEY;
import static com.ramapay.app.entity.Operation.CREATE_PRIVATE_KEY;
import static com.ramapay.app.entity.Operation.FETCH_MNEMONIC;
import static com.ramapay.app.entity.Operation.IMPORT_HD_KEY;
import static com.ramapay.app.entity.Operation.UPGRADE_HD_KEY;
import static com.ramapay.app.entity.tokenscript.TokenscriptFunction.ZERO_ADDRESS;
import static com.ramapay.app.service.KeyService.AuthenticationLevel.STRONGBOX_NO_AUTHENTICATION;
import static com.ramapay.app.service.KeyService.AuthenticationLevel.TEE_NO_AUTHENTICATION;
import static com.ramapay.app.service.KeystoreAccountService.KEYSTORE_FOLDER;
import static com.ramapay.app.service.KeystoreAccountService.bytesFromSignature;
import static com.ramapay.app.service.LegacyKeystore.getLegacyPassword;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.StrongBoxUnavailableException;
import android.security.keystore.UserNotAuthenticatedException;
import android.util.Pair;
import android.widget.Toast;

import androidx.annotation.RequiresApi;

import com.ramapay.app.BuildConfig;
import com.ramapay.app.R;
import com.ramapay.app.entity.AnalyticsProperties;
import com.ramapay.app.entity.AuthenticationCallback;
import com.ramapay.app.entity.AuthenticationFailType;
import com.ramapay.app.entity.CreateWalletCallbackInterface;
import com.ramapay.app.entity.ImportWalletCallback;
import com.ramapay.app.entity.Operation;
import com.ramapay.app.entity.PinAuthenticationCallbackInterface;
import com.ramapay.app.entity.ServiceErrorException;
import com.ramapay.app.entity.SignAuthenticationCallback;
import com.ramapay.app.entity.Wallet;
import com.ramapay.app.entity.cryptokeys.KeyEncodingType;
import com.ramapay.app.entity.cryptokeys.KeyServiceException;
import com.ramapay.app.util.Utils;
import com.ramapay.app.widget.AWalletAlertDialog;
import com.ramapay.app.widget.SignTransactionDialog;
import com.ramapay.hardware.HardwareCallback;
import com.ramapay.hardware.HardwareDevice;
import com.ramapay.hardware.SignatureFromKey;
import com.ramapay.hardware.SignatureReturnType;

import org.web3j.crypto.Credentials;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Enumeration;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;

import timber.log.Timber;
import wallet.core.jni.CoinType;
import wallet.core.jni.Curve;
import wallet.core.jni.HDWallet;
import wallet.core.jni.Hash;
import wallet.core.jni.Mnemonic;
import wallet.core.jni.PrivateKey;

public class KeyService implements AuthenticationCallback, PinAuthenticationCallbackInterface, HardwareCallback
{
    private static final String TAG = "HDWallet";
    private static final int AUTHENTICATION_DURATION_SECONDS = 30;
    private static final String BLOCK_MODE = KeyProperties.BLOCK_MODE_GCM;
    private static final String PADDING = KeyProperties.ENCRYPTION_PADDING_NONE;

    public static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    public static final String LEGACY_CIPHER_ALGORITHM = "AES/CBC/PKCS7Padding";
    public static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";

    //This value determines the time interval between the user swiping away the backup warning notice and it re-appearing
    public static final long TIME_BETWEEN_BACKUP_WARNING_MILLIS = 1000L * 60L * 60L * 24L * 30L; //30 days //1000 * 60 * 3; //3 minutes for testing

    public enum AuthenticationLevel
    {
        NOT_SET, TEE_NO_AUTHENTICATION, TEE_AUTHENTICATION, STRONGBOX_NO_AUTHENTICATION, STRONGBOX_AUTHENTICATION
    }

    //Return values for requesting security upgrade of key
    public enum UpgradeKeyResultType
    {
        REQUESTING_SECURITY, NO_SCREENLOCK, ALREADY_LOCKED, ERROR, SUCCESSFULLY_UPGRADED
    }

    public static class UpgradeKeyResult
    {
        public final UpgradeKeyResultType result;
        public final String message;

        public UpgradeKeyResult(UpgradeKeyResultType res, String msg)
        {
            result = res;
            message = msg;
        }
    }

    //Check performed at service start to determine API strength
    private enum SecurityStatus
    {
        NOT_CHECKED, HAS_NO_TEE, HAS_TEE, HAS_STRONGBOX
    }

    private static final int DEFAULT_KEY_STRENGTH = 128;
    private final Context context;
    private Activity activity;

    private final HardwareDevice hardwareDevice;

    //Used for keeping the Ethereum account information between re-entrant calls
    private Wallet currentWallet;
    private int derivingAccountIndex = -1; //Used for HD account derivation after authentication

    private AuthenticationLevel authLevel;
    private SignTransactionDialog signDialog;
    private AWalletAlertDialog alertDialog;
    private CreateWalletCallbackInterface callbackInterface;
    private ImportWalletCallback importCallback;
    private SignAuthenticationCallback signCallback;
    private Runnable discoveryCallback; // Callback for DISCOVER_ACCOUNTS operation
    private final AnalyticsServiceType<AnalyticsProperties> analyticsService;
    private boolean requireAuthentication = false;

    private static SecurityStatus securityStatus = SecurityStatus.NOT_CHECKED;
    
    // Some manufacturers have problematic KeyStore implementations
    // iQOO is a Vivo sub-brand that may report separately
    private static final String[] PROBLEMATIC_MANUFACTURERS = {"oppo", "vivo", "iqoo", "realme", "oneplus", "xiaomi", "huawei", "honor", "tecno", "infinix"};

    public Context getContext()
    {
        return context;
    }
    
    /**
     * Check if running on an emulator
     */
    private static boolean isEmulator()
    {
        return Build.FINGERPRINT.contains("generic")
            || Build.FINGERPRINT.contains("emulator")
            || Build.MODEL.contains("sdk_gphone")
            || Build.MODEL.contains("Emulator")
            || Build.MODEL.contains("Android SDK")
            || Build.MANUFACTURER.contains("Genymotion")
            || Build.HARDWARE.contains("goldfish")
            || Build.HARDWARE.contains("ranchu")
            || Build.PRODUCT.contains("sdk")
            || Build.PRODUCT.contains("google_sdk")
            || Build.PRODUCT.contains("sdk_gphone");
    }
    
    /**
     * Check if this device has a manufacturer known to have KeyStore issues
     */
    private static boolean isProblematicDevice()
    {
        // Emulators often have keystore issues
        if (isEmulator())
        {
            Timber.tag(TAG).w("Running on emulator - using software key for reliability");
            return true;
        }
        
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        for (String problematic : PROBLEMATIC_MANUFACTURERS)
        {
            if (manufacturer.contains(problematic))
            {
                Timber.tag(TAG).w("Device manufacturer %s may have KeyStore issues", manufacturer);
                return true;
            }
        }
        return false;
    }

    public KeyService(Context ctx, AnalyticsServiceType<AnalyticsProperties> analyticsService)
    {
        System.loadLibrary("TrustWalletCore");
        this.context = ctx;
        this.analyticsService = analyticsService;
        this.hardwareDevice = new HardwareDevice(this);
        checkSecurity();
    }

    /**
     * Create a new HD key.
     * call createHDKey which creates a new HD wallet, stores the mnemonic and calls HDKeyCreated callback
     * We use a callback to allow creation of a key with authentication lock
     *
     * @param callingActivity
     * @param callback
     */
    public void createNewHDKey(Activity callingActivity, CreateWalletCallbackInterface callback)
    {
        activity = callingActivity;
        callbackInterface = callback;
        createHDKey();
    }

    /**
     * Create and encrypt/store an authentication-locked keystore password for importing a keystore.
     * Flow for importing a private key is almost identical
     *
     * Flow is as follows:
     *
     * 1. Obtain authentication event - pop up the unlock dialog.
     * 2. After authentication event, proceed to authenticatePass and switch through to createPassword()
     * 3. Create a new strong keystore password, store the password.
     * 4. Call the KeystoreValidated callback.
     *
     * @param address
     * @param callingActivity
     * @param callback
     */
    public void createKeystorePassword(String address, Activity callingActivity, ImportWalletCallback callback)
    {
        activity = callingActivity;
        importCallback = callback;
        currentWallet = new Wallet(address);
        checkAuthentication(CREATE_KEYSTORE_KEY);
    }

    /**
     * Flow is the same as createKeystorePassword but on successful completion of key generation call
     * importCallback.KeyValidated
     *
     * @param address
     * @param callingActivity
     * @param callback
     */
    public void createPrivateKeyPassword(String address, Activity callingActivity, ImportWalletCallback callback)
    {
        activity = callingActivity;
        importCallback = callback;
        currentWallet = new Wallet(address);
        checkAuthentication(CREATE_PRIVATE_KEY);
    }

    /**
     * Encrypt and store mnemonic for HDWallet
     *
     * 1. Check valid seed phrase, generate HDWallet and store the mnemonic without authentication lock
     * 2. Obtain authentication event.
     * 3. After authentication pass through to authenticatePass and switch to importHDKey()
     * 4. ImportHDKey() restores the mnemonic and replaces the key with an authentication locked key.
     * 5. KeyValidated callback to pass control back to viewModel
     *
     * @param seedPhrase
     * @param callingActivity
     * @param callback
     */
    public void importHDKey(String seedPhrase, Activity callingActivity, ImportWalletCallback callback)
    {
        activity = callingActivity;
        importCallback = callback;

        //cursory check for valid key import
        if (!Mnemonic.isValid(seedPhrase))
        {
            callback.walletValidated(null, KeyEncodingType.SEED_PHRASE_KEY, AuthenticationLevel.NOT_SET);
        }
        else
        {
            HDWallet newWallet = new HDWallet(seedPhrase, "");
            boolean stored = storeHDKey(newWallet, false); //store encrypted bytes in case of re-entry
            if (!stored)
            {
                Timber.tag(TAG).e("Failed to store HD key during import - initial storage failed");
                // Show helpful error dialog with troubleshooting tips
                keyFailure(context.getString(R.string.key_store_failed));
                return;
            }
            checkAuthentication(IMPORT_HD_KEY);
        }
    }

    /**
     * Fetch mnemonic from storage
     *
     * 1. call unpackMnemonic
     * 2. if authentication required, get authentication event and call unpackMnemonic
     * 3. return mnemonic to FetchMnemonic callback
     *
     * @param wallet
     * @param callingActivity
     * @param callback
     */
    public void getMnemonic(Wallet wallet, Activity callingActivity, CreateWalletCallbackInterface callback)
    {
        activity = callingActivity;
        currentWallet = wallet;
        callbackInterface = callback;

        //unlock key

        try
        {
            String mnemonic = unpackMnemonic();
            callback.fetchMnemonic(mnemonic);
        }
        catch (KeyServiceException e)
        {
            keyFailure(e.getMessage());
        }
        catch (UserNotAuthenticatedException e)
        {
            callingActivity.runOnUiThread(() ->
                    checkAuthentication(FETCH_MNEMONIC));
        }
    }

    /**
     * 1. Get authentication event if required.
     * 2. Resume operation at getAuthenticationForSignature
     * 3. get mnemonic/password
     * 4. rebuild private key
     * 5. sign.
     *
     * @param wallet
     * @param callingActivity
     * @param callback
     */
    public void getAuthenticationForSignature(Wallet wallet, Activity callingActivity, SignAuthenticationCallback callback)
    {
        signCallback = callback;
        activity = callingActivity;
        currentWallet = wallet;

        switch (wallet.type)
        {
            case HARDWARE: //bypass this step as with hardware we obtain authentication simultaneously with signing
            case KEYSTORE_LEGACY:
                signCallback.gotAuthorisation(true); //Legacy keys don't require authentication
                break;
            case KEYSTORE:
            case HDKEY:
                checkAuthentication(Operation.CHECK_AUTHENTICATION);
                break;
            case NOT_DEFINED:
            case TEXT_MARKER:
            case WATCH:
                signCallback.gotAuthorisation(false);
                break;
        }
    }

    @Override
    public void signedMessageFromHardware(SignatureFromKey returnSig)
    {
        signCallback.gotSignature(returnSig);
    }

    @Override
    public void onCardReadStart()
    {
        //TODO: Display card read in progress
    }

    @Override
    public void hardwareCardError(String message)
    {
        signCallback.signingError(message);
    }

    public void setRequireAuthentication()
    {
        requireAuthentication = true;
    }

    /**
     * Upgrade key security
     *
     * 1. Get authentication, and then execute 'upgradeKey()' from authenticatePass
     * 2. Upgrade key reads the mnemonic/password, then calls storeEncryptedBytes with authentication.
     * 3. returns result and flow back to callee via signCallback.CreatedKey
     *
     *
     * @param wallet
     * @param callingActivity
     * @return
     */
    public UpgradeKeyResult upgradeKeySecurity(Wallet wallet, Activity callingActivity)
    {
        signCallback = null;
        activity = callingActivity;
        currentWallet = wallet;
        //first check we have ability to generate the key
        if (!deviceIsLocked())
        {
            return new UpgradeKeyResult(UpgradeKeyResultType.NO_SCREENLOCK, "Device is not locked");
        }

        return upgradeKey();
    }

    /**
     * SignData
     *
     * Flow for this function is by necessity simpler - this function is called from code that doesn't have access to an Activity, so can't create
     * any signing dialog. The authentication event must be generated prior to entering the signing flow.
     *
     * If HDWallet - decrypt mnemonic, regenerate private key, generate digest, sign digest using Trezor libs.
     * If Keystore - fetch keystore JSON file, decrypt keystore password, regenerate Web3j Credentials and sign.
     *
     * @param wallet
     * @param TBSdata
     * @return
     */
    synchronized SignatureFromKey signData(Wallet wallet, byte[] TBSdata)
    {
        SignatureFromKey returnSig = new SignatureFromKey();

        currentWallet = wallet;
        switch (wallet.type)
        {
            case KEYSTORE_LEGACY:
            case KEYSTORE:
                returnSig = signWithKeystore(TBSdata);
                break;
            case HDKEY:
                try
                {
                    String mnemonic = unpackMnemonic();
                    HDWallet newWallet = new HDWallet(mnemonic, "");
                    PrivateKey pk = newWallet.getKeyForCoin(CoinType.ETHEREUM);
                    byte[] digest = Hash.keccak256(TBSdata);
                    returnSig.signature = pk.sign(digest, Curve.SECP256K1);
                    returnSig.sigType = SignatureReturnType.SIGNATURE_GENERATED;
                }
                catch (KeyServiceException | UserNotAuthenticatedException e)
                {
                    returnSig.failMessage = e.getMessage();
                }
                break;
            case WATCH:
                returnSig.failMessage = context.getString(R.string.action_watch_account);
                break;
            case HARDWARE:
                hardwareDevice.activateReader(activity);
                hardwareDevice.setSigningData(org.web3j.crypto.Hash.sha3(TBSdata));
                returnSig.sigType = SignatureReturnType.SIGNING_POSTPONED;
                break;
            case NOT_DEFINED:
            case TEXT_MARKER:
            default:
                returnSig.failMessage = context.getString(R.string.no_key);
        }

        return returnSig;
    }

    /**
     * Fetches keystore password for export/backup of keystore
     *
     * @param wallet
     * @param callingActivity
     * @param callback
     */
    public void getPassword(Wallet wallet, Activity callingActivity, CreateWalletCallbackInterface callback)
    {
        activity = callingActivity;
        currentWallet = wallet;
        callbackInterface = callback;

        String password;

        try
        {
            switch (wallet.type)
            {
                case KEYSTORE:
                    password = unpackMnemonic();
                    callback.fetchMnemonic(password);
                    break;
                case KEYSTORE_LEGACY:
                    password = new String(getLegacyPassword(context, wallet.address));
                    callback.fetchMnemonic(password);
                    break;
                default:
                    break;
            }
        }
        catch (UserNotAuthenticatedException e)
        {
            checkAuthentication(FETCH_MNEMONIC);
        }
        catch (ServiceErrorException e)
        {
            //Legacy keystore error
            if (!BuildConfig.DEBUG) analyticsService.recordException(e);
            e.printStackTrace();
        }
        catch (KeyServiceException e)
        {
            keyFailure(e.getMessage());
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public void resetSigningDialog()
    {
        if (signDialog != null) signDialog.close();
        signDialog = null;
    }

    private synchronized String unpackMnemonic() throws KeyServiceException, UserNotAuthenticatedException
    {
        try
        {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            
            // For derived HD accounts, use the parent wallet's address to find the mnemonic
            String walletAddressToUse = currentWallet.address;
            if (currentWallet.isDerivedHDAccount() && currentWallet.parentAddress != null && !currentWallet.parentAddress.isEmpty())
            {
                walletAddressToUse = currentWallet.parentAddress;
            }
            
            String matchingAddr = findMatchingAddrInKeyStore(walletAddressToUse);
            if (!keyStore.containsAlias(matchingAddr))
            {
                // Check if the encrypted file exists - if not, wallet was never properly created
                File encryptedFile = new File(getFilePath(context, matchingAddr));
                if (!encryptedFile.exists())
                {
                    throw new KeyServiceException("Wallet was not properly initialized.\n\nPlease create or import your wallet again.");
                }
                // File exists but key is gone - device security issue
                throw new KeyServiceException("Wallet key not found in secure storage.\n\nThis may happen on some devices (like Oppo, Vivo) due to security restrictions.\n\nPlease re-import your wallet using your recovery phrase.");
            }

            //create a stream to the encrypted bytes
            FileInputStream encryptedHDKeyBytes = new FileInputStream(getFilePath(context, matchingAddr));
            SecretKey secretKey = (SecretKey) keyStore.getKey(matchingAddr, null);
            boolean ivExists = new File(getFilePath(context, matchingAddr + "iv")).exists();
            byte[] iv = null;

            if (ivExists)
                iv = readBytesFromFile(getFilePath(context, matchingAddr + "iv"));
            if (iv == null || iv.length == 0)
            {
                throw new KeyServiceException(context.getString(R.string.cannot_read_encrypt_file));
            }
            Cipher outCipher = Cipher.getInstance(CIPHER_ALGORITHM);
            final GCMParameterSpec spec = new GCMParameterSpec(128, iv);
            outCipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
            CipherInputStream cipherInputStream = new CipherInputStream(encryptedHDKeyBytes, outCipher);
            byte[] mnemonicBytes = readBytesFromStream(cipherInputStream);
            return new String(mnemonicBytes);
        }
        catch (InvalidKeyException e)
        {
            if (e instanceof UserNotAuthenticatedException)
            {
                throw new UserNotAuthenticatedException(context.getString(R.string.authentication_error));
            }
            else
            {
                throw new KeyServiceException(e.getMessage());
            }
        }
        catch (UnrecoverableKeyException e)
        {
            throw new KeyServiceException(context.getString(R.string.device_security_changed));
        }
        catch (IOException | CertificateException | KeyStoreException | NoSuchAlgorithmException | NoSuchPaddingException | InvalidAlgorithmParameterException e)
        {
            e.printStackTrace();
            throw new KeyServiceException(e.getMessage());
        }
        catch (Exception e)
        {
            throw new KeyServiceException(e.getMessage());
        }
    }

    public Pair<KeyExceptionType, String> testCipher(String walletAddress, String cipherAlgorithm)
    {
        KeyExceptionType retVal = KeyExceptionType.UNKNOWN;
        String keyData = null;
        try
        {
            String encryptedDataFilePath = KeyService.getFilePath(context, walletAddress);
            String keyIv = KeyService.getFilePath(context, walletAddress + "iv");
            boolean ivExists = new File(keyIv).exists();
            boolean aliasExists = new File(encryptedDataFilePath).exists();

            if (!ivExists)
            {
                retVal = KeyExceptionType.IV_NOT_FOUND;
                throw new Exception("iv file doesn't exist");
            }
            if (!aliasExists)
            {
                retVal = KeyExceptionType.ENCRYPTED_FILE_NOT_FOUND;
                throw new Exception("Key file doesn't exist");
            }

            //test legacy key
            byte[] iv = KeyService.readBytesFromFile(keyIv);

            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            SecretKey secretKey = (SecretKey) keyStore.getKey(walletAddress, null);

            Cipher outCipher = Cipher.getInstance(cipherAlgorithm);
            final AlgorithmParameterSpec spec = cipherAlgorithm.equals(CIPHER_ALGORITHM) ? new GCMParameterSpec(128, iv) : new IvParameterSpec(iv);
            outCipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
            CipherInputStream cipherInputStream = new CipherInputStream(new FileInputStream(encryptedDataFilePath), outCipher);
            byte[] keyBytes = KeyService.readBytesFromStream(cipherInputStream);
            keyData = new String(keyBytes);
            retVal = KeyExceptionType.SUCCESSFUL_DECODE;
        }
        catch (UserNotAuthenticatedException e)
        {
            retVal = KeyExceptionType.REQUIRES_AUTH;
        }
        catch (InvalidKeyException e)
        {
            //Wrong spec
            retVal = KeyExceptionType.INVALID_CIPHER;
        }
        catch (Exception e)
        {
            // Other
        }

        return new Pair<>(retVal, keyData);
    }

    private void createHDKey()
    {
        HDWallet newWallet = new HDWallet(DEFAULT_KEY_STRENGTH, "");
        boolean success = storeHDKey(newWallet, false); //create non-authenticated key initially
        
        // Verify the key was stored properly by attempting to read it back
        if (success)
        {
            success = verifyStoredKey(currentWallet.address, newWallet.mnemonic());
        }
        
        // Callback must be invoked on main thread for UI operations
        final boolean finalSuccess = success;
        final String walletAddress = success ? currentWallet.address : null;
        if (callbackInterface != null)
        {
            if (activity != null)
            {
                activity.runOnUiThread(() -> 
                    callbackInterface.HDKeyCreated(walletAddress, context, authLevel));
            }
            else
            {
                callbackInterface.HDKeyCreated(walletAddress, context, authLevel);
            }
        }
    }
    
    /**
     * Verify that a stored key can be read back correctly
     * This ensures the key storage was successful and the key is recoverable
     */
    private boolean verifyStoredKey(String address, String expectedMnemonic)
    {
        try
        {
            // Check if files exist
            String encryptedFilePath = getFilePath(context, address);
            String ivFilePath = getFilePath(context, address + "iv");
            
            File encryptedFile = new File(encryptedFilePath);
            File ivFile = new File(ivFilePath);
            
            if (!encryptedFile.exists() || !ivFile.exists())
            {
                Timber.tag(TAG).e("Key verification failed: files don't exist for address %s", address);
                return false;
            }
            
            // Verify KeyStore contains the key
            if (!hasKeystore(address))
            {
                Timber.tag(TAG).e("Key verification failed: KeyStore doesn't contain key for address %s", address);
                return false;
            }
            
            // Try to decrypt and verify the mnemonic matches
            Pair<KeyExceptionType, String> testResult = testCipher(address, CIPHER_ALGORITHM);
            if (testResult.first == KeyExceptionType.SUCCESSFUL_DECODE)
            {
                // Verify the decrypted mnemonic matches what we stored
                if (expectedMnemonic != null && expectedMnemonic.equals(testResult.second))
                {
                    Timber.tag(TAG).d("Key verification successful for address %s", address);
                    return true;
                }
                else
                {
                    Timber.tag(TAG).e("Key verification failed: mnemonic mismatch for address %s", address);
                    return false;
                }
            }
            else if (testResult.first == KeyExceptionType.REQUIRES_AUTH)
            {
                // Key requires authentication to decrypt - this is expected for auth-locked keys
                // At minimum we verified the key exists, so return true
                Timber.tag(TAG).d("Key verification: auth required for address %s (key exists)", address);
                return true;
            }
            else
            {
                Timber.tag(TAG).e("Key verification failed: cipher test returned %s for address %s", testResult.first, address);
                return false;
            }
        }
        catch (Exception e)
        {
            Timber.tag(TAG).e(e, "Key verification exception for address %s", address);
            return false;
        }
    }

    /**
     * Called after an authentication event was required, and the user has completed the authentication event
     */
    private void importHDKey()
    {
        boolean requiresAuthentication = !Utils.isRunningTest();
        //first recover the seed phrase from non-authlocked key. This removes the need to keep the seed phrase as a member on the heap
        // - making the key operation more secure
        try
        {
            String seedPhrase = unpackMnemonic();
            HDWallet newWallet = new HDWallet(seedPhrase, "");
            boolean success = storeHDKey(newWallet, requiresAuthentication);
            
            // Verify the key was stored properly
            if (success)
            {
                success = verifyStoredKey(currentWallet.address, seedPhrase);
            }
            
            String reportAddress = success ? currentWallet.address : null;
            
            if (!success)
            {
                Timber.tag(TAG).e("Import HD key failed verification for address %s", currentWallet.address);
                keyFailure(context.getString(R.string.key_store_failed));
                return;
            }
            
            importCallback.walletValidated(reportAddress, KeyEncodingType.SEED_PHRASE_KEY, authLevel);
        }
        catch (UserNotAuthenticatedException | KeyServiceException e)
        {
            keyFailure(e.getMessage());
        }
    }

    /**
     * Reached after authentication has been provided
     * @return
     */
    private UpgradeKeyResult upgradeKey()
    {
        try
        {
            String secretData = null;

            switch (currentWallet.type)
            {
                case HDKEY:
                case KEYSTORE:
                    secretData = unpackMnemonic();
                    break;
                case KEYSTORE_LEGACY:
                    secretData = new String(getLegacyPassword(context, currentWallet.address));
                    break;
                default:
                    break;
            }

            if (secretData == null)
            {
                return new UpgradeKeyResult(UpgradeKeyResultType.ERROR, context.getString(R.string.no_key_found));
            }

            boolean keyStored = storeEncryptedBytes(secretData.getBytes(), true, currentWallet.address);
            if (keyStored)
            {
                return new UpgradeKeyResult(UpgradeKeyResultType.SUCCESSFULLY_UPGRADED, "");
            }
            else
            {
                return new UpgradeKeyResult(UpgradeKeyResultType.ERROR, context.getString(R.string.unable_store_key, currentWallet.address));
            }
        }
        catch (ServiceErrorException e)
        {
            //Legacy keystore error
            if (!BuildConfig.DEBUG) analyticsService.recordException(e);
            e.printStackTrace();
            return new UpgradeKeyResult(UpgradeKeyResultType.ERROR, e.getLocalizedMessage());
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return new UpgradeKeyResult(UpgradeKeyResultType.ERROR, e.getLocalizedMessage());
        }
    }

    private synchronized boolean storeHDKey(HDWallet newWallet, boolean keyRequiresAuthentication)
    {
        PrivateKey pk = newWallet.getKeyForCoin(CoinType.ETHEREUM);
        currentWallet = new Wallet(CoinType.ETHEREUM.deriveAddress(pk));

        return storeEncryptedBytes(newWallet.mnemonic().getBytes(), keyRequiresAuthentication, currentWallet.address);
    }

    /**
     * Derive a new HD account from an existing wallet's seed phrase
     * Uses BIP44 path: m/44'/60'/0'/0/{index}
     * 
     * @param parentWallet The parent HD wallet containing the seed phrase
     * @param accountIndex The account index to derive (0, 1, 2, etc.)
     * @param callingActivity The activity for showing authentication dialog
     * @param callback Callback to return the derived wallet address
     */
    public void deriveNewHDAccount(Wallet parentWallet, int accountIndex, Activity callingActivity, CreateWalletCallbackInterface callback)
    {
        callbackInterface = callback;
        currentWallet = parentWallet;
        derivingAccountIndex = accountIndex;
        activity = callingActivity;
        
        try
        {
            performHDAccountDerivation();
        }
        catch (KeyServiceException e)
        {
            Timber.tag(TAG).e(e, "Failed to derive HD account");
            keyFailure(e.getMessage());
        }
        catch (UserNotAuthenticatedException e)
        {
            Timber.tag(TAG).d("Authentication required for HD account derivation");
            // Need to get authentication first, then retry
            if (activity != null)
            {
                activity.runOnUiThread(() ->
                        checkAuthentication(Operation.DERIVE_HD_ACCOUNT));
            }
            else
            {
                keyFailure(context.getString(R.string.authentication_error));
            }
        }
    }
    
    /**
     * Perform the actual HD account derivation after authentication (if needed)
     */
    private void performHDAccountDerivation() throws KeyServiceException, UserNotAuthenticatedException
    {
        String mnemonic = unpackMnemonic();
        HDWallet hdWallet = new HDWallet(mnemonic, "");
        
        // Derive key at the specified index using BIP44 path
        // m/44'/60'/0'/0/{index}
        String derivationPath = "m/44'/60'/0'/0/" + derivingAccountIndex;
        PrivateKey pk = hdWallet.getKey(CoinType.ETHEREUM, derivationPath);
        String newAddress = CoinType.ETHEREUM.deriveAddress(pk);
        
        Timber.tag(TAG).d("Derived HD account at index %d: %s", derivingAccountIndex, newAddress);
        
        // Callback must be invoked on main thread for UI operations
        if (callbackInterface != null)
        {
            if (activity != null)
            {
                activity.runOnUiThread(() -> 
                    callbackInterface.HDKeyCreated(newAddress, context, authLevel));
            }
            else
            {
                callbackInterface.HDKeyCreated(newAddress, context, authLevel);
            }
        }
    }

    /**
     * Get the private key at a specific HD derivation index
     * @param wallet The parent HD wallet
     * @param accountIndex The account index
     * @return The private key bytes, or null if failed
     */
    public byte[] getPrivateKeyAtIndex(Wallet wallet, int accountIndex)
    {
        try
        {
            currentWallet = wallet;
            String mnemonic = unpackMnemonic();
            HDWallet hdWallet = new HDWallet(mnemonic, "");
            
            String derivationPath = "m/44'/60'/0'/0/" + accountIndex;
            PrivateKey pk = hdWallet.getKey(CoinType.ETHEREUM, derivationPath);
            return pk.data();
        }
        catch (KeyServiceException | UserNotAuthenticatedException e)
        {
            Timber.tag(TAG).e(e, "Failed to get private key at index %d", accountIndex);
            return null;
        }
    }

    /**
     * Get the address at a specific HD derivation index (without needing private key)
     * @param wallet The parent HD wallet
     * @param accountIndex The account index
     * @return The address, or null if failed
     */
    public String getAddressAtIndex(Wallet wallet, int accountIndex)
    {
        try
        {
            currentWallet = wallet;
            String mnemonic = unpackMnemonic();
            HDWallet hdWallet = new HDWallet(mnemonic, "");
            
            String derivationPath = "m/44'/60'/0'/0/" + accountIndex;
            PrivateKey pk = hdWallet.getKey(CoinType.ETHEREUM, derivationPath);
            String address = CoinType.ETHEREUM.deriveAddress(pk);
            return address;
        }
        catch (KeyServiceException | UserNotAuthenticatedException e)
        {
            Timber.tag(TAG).e(e, "Failed to get address at index %d", accountIndex);
            return null;
        }
    }
    
    /**
     * Get the address at a specific HD derivation index with authentication handling
     * Throws UserNotAuthenticatedException if authentication is required
     * @param wallet The parent HD wallet
     * @param accountIndex The account index
     * @return The address
     * @throws UserNotAuthenticatedException if user needs to authenticate
     * @throws KeyServiceException on other errors
     */
    public String getAddressAtIndexAuthenticated(Wallet wallet, int accountIndex) 
            throws UserNotAuthenticatedException, KeyServiceException
    {
        currentWallet = wallet;
        String mnemonic = unpackMnemonic();
        HDWallet hdWallet = new HDWallet(mnemonic, "");
        
        String derivationPath = "m/44'/60'/0'/0/" + accountIndex;
        PrivateKey pk = hdWallet.getKey(CoinType.ETHEREUM, derivationPath);
        String address = CoinType.ETHEREUM.deriveAddress(pk);
        return address;
    }

    private synchronized boolean storeEncryptedBytes(byte[] data, boolean createAuthLocked, String fileName)
    {
        // Try up to 3 times with exponential backoff for transient KeyStore errors
        int maxRetries = 3;
        int retryDelayMs = 100;
        
        for (int attempt = 1; attempt <= maxRetries; attempt++)
        {
            boolean success = attemptStoreEncryptedBytes(data, createAuthLocked, fileName);
            if (success)
            {
                return true;
            }
            
            if (attempt < maxRetries)
            {
                Timber.tag(TAG).w("Key storage attempt %d failed, retrying in %dms...", attempt, retryDelayMs);
                try
                {
                    Thread.sleep(retryDelayMs);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    break;
                }
                retryDelayMs *= 2; // Exponential backoff
            }
        }
        
        Timber.tag(TAG).e("All %d attempts to store key failed for address %s", maxRetries, fileName);
        return false;
    }
    
    private boolean attemptStoreEncryptedBytes(byte[] data, boolean createAuthLocked, String fileName)
    {
        KeyStore keyStore = null;
        try
        {
            keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);

            String encryptedHDKeyPath = getFilePath(context, fileName);
            KeyGenerator keyGenerator = getMaxSecurityKeyGenerator(fileName, createAuthLocked);
            
            // Check if keyGenerator was successfully initialized
            if (keyGenerator == null)
            {
                Timber.tag(TAG).e("KeyGenerator is null - hardware keystore unavailable for address %s", fileName);
                throw new ServiceErrorException(
                        ServiceErrorException.ServiceErrorCode.KEY_STORE_ERROR,
                        "Unable to create secure key storage. Please ensure your device has a screen lock set up.");
            }
            
            final SecretKey secretKey = keyGenerator.generateKey();
            final Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] iv = cipher.getIV();
            String ivPath = getFilePath(context, fileName + "iv");
            boolean success = writeBytesToFile(ivPath, iv);
            if (!success)
            {
                //deleteKey(fileName);
                throw new ServiceErrorException(
                        ServiceErrorException.ServiceErrorCode.FAIL_TO_SAVE_IV_FILE,
                        "Failed to create the iv file for: " + fileName + "iv");
            }

            try (CipherOutputStream cipherOutputStream = new CipherOutputStream(
                    new FileOutputStream(encryptedHDKeyPath),
                    cipher))
            {
                cipherOutputStream.write(data);
            }
            catch (Exception ex)
            {
                //deleteKey(fileName);
                throw new ServiceErrorException(
                        ServiceErrorException.ServiceErrorCode.KEY_STORE_ERROR,
                        "Failed to create the file for: " + fileName);
            }

            // Verify the key was actually stored in the KeyStore
            // Some devices (Oppo, Vivo) and emulators may silently fail to persist keys
            // Use longer delay on emulators as they are slower
            int verifyDelayMs = isEmulator() ? 500 : 100;
            int maxVerifyAttempts = isEmulator() ? 5 : 3;
            
            for (int verifyAttempt = 0; verifyAttempt < maxVerifyAttempts; verifyAttempt++)
            {
                try { Thread.sleep(verifyDelayMs); } catch (InterruptedException ignored) {}
                keyStore.load(null); // Reload to ensure fresh state
                
                if (keyStore.containsAlias(fileName))
                {
                    Timber.tag(TAG).i("Key successfully stored and verified for: %s (attempt %d)", fileName, verifyAttempt + 1);
                    return true;
                }
                
                Timber.tag(TAG).w("Key verification attempt %d failed for: %s", verifyAttempt + 1, fileName);
            }
            
            // Key really didn't persist - this device has keystore issues
            Timber.tag(TAG).e("Key verification failed - key not found after %d attempts for: %s", maxVerifyAttempts, fileName);
            deleteKey(fileName);
            throw new ServiceErrorException(
                    ServiceErrorException.ServiceErrorCode.KEY_STORE_ERROR,
                    "Secure key storage verification failed. Your device may have incompatible security hardware. Please try again or use a different device.");
        }
        catch (ServiceErrorException ex)
        {
            deleteKey(fileName);
            Timber.tag(TAG).e(ex, "Key store service error");
            // Don't re-throw, just return false - error will be handled by caller
        }
        catch (Exception ex)
        {
            deleteKey(fileName);
            Timber.tag(TAG).e(ex, "Key store error: %s", ex.getMessage());
        }

        return false;
    }

    private KeyGenerator getMaxSecurityKeyGenerator(String keyAddress, boolean useAuthentication)
    {
        KeyGenerator keyGenerator = null;
        boolean keyInitialized = false;

        try
        {
            keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEY_STORE);

            // For problematic devices, try software key first to avoid hardware keystore issues
            if (isProblematicDevice())
            {
                Timber.tag(TAG).w("Problematic device detected, trying software key first");
                if (tryInitSoftwareKey(keyGenerator, keyAddress))
                {
                    authLevel = TEE_NO_AUTHENTICATION;
                    keyInitialized = true;
                    Timber.tag(TAG).i("Software key used for problematic device: %s", Build.MANUFACTURER);
                }
            }
            
            if (!keyInitialized && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && tryInitStrongBoxKey(keyGenerator, keyAddress, useAuthentication))
            {
                if (useAuthentication) authLevel = AuthenticationLevel.STRONGBOX_AUTHENTICATION;
                else authLevel = STRONGBOX_NO_AUTHENTICATION;
                keyInitialized = true;
            }
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && tryInitStrongBoxKey(keyGenerator, keyAddress, false))
            {
                authLevel = STRONGBOX_NO_AUTHENTICATION;
                keyInitialized = true;
            }
            else if (tryInitTEEKey(keyGenerator, keyAddress, useAuthentication))
            {
                //fallback to non Strongbox
                if (useAuthentication) authLevel = AuthenticationLevel.TEE_AUTHENTICATION;
                else authLevel = TEE_NO_AUTHENTICATION;
                keyInitialized = true;
            }
            else if (tryInitTEEKey(keyGenerator, keyAddress, false))
            {
                authLevel = TEE_NO_AUTHENTICATION;
                keyInitialized = true;
            }
            
            // Final fallback: try software-only key without user authentication
            // This is less secure but ensures the wallet can still be created on devices
            // with problematic hardware keystore implementations
            if (!keyInitialized)
            {
                Timber.tag(TAG).w("Hardware-backed keystore failed, trying software fallback");
                if (tryInitSoftwareKey(keyGenerator, keyAddress))
                {
                    authLevel = TEE_NO_AUTHENTICATION; // Treat as no-auth for compatibility
                    keyInitialized = true;
                    Timber.tag(TAG).i("Software key fallback successful for address %s", keyAddress);
                }
            }
            
            if (!keyInitialized)
            {
                Timber.tag(TAG).e("Failed to initialize any key type for address %s", keyAddress);
                return null;
            }
        }
        catch (NoSuchAlgorithmException | NoSuchProviderException ex)
        {
            Timber.tag(TAG).e(ex, "KeyGenerator not available");
            return null;
        }
        catch (Exception e)
        {
            Timber.tag(TAG).e(e, "Unexpected error in getMaxSecurityKeyGenerator");
            authLevel = AuthenticationLevel.NOT_SET;
            return null;
        }

        return keyGenerator;
    }
    
    /**
     * Software-only key as final fallback when hardware keystore is unavailable
     * This is less secure than TEE/StrongBox but ensures basic functionality
     */
    private boolean tryInitSoftwareKey(KeyGenerator keyGenerator, String keyAddress)
    {
        try
        {
            keyGenerator.init(new KeyGenParameterSpec.Builder(
                    keyAddress,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(BLOCK_MODE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .setRandomizedEncryptionRequired(true)
                    .setEncryptionPaddings(PADDING)
                    .build());
            return true;
        }
        catch (Exception e)
        {
            Timber.tag(TAG).e(e, "Software key fallback also failed");
            return false;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.P)
    private boolean tryInitStrongBoxKey(KeyGenerator keyGenerator, String keyAddress, boolean useAuthentication) throws InvalidAlgorithmParameterException
    {
        try
        {
            keyGenerator.init(new KeyGenParameterSpec.Builder(
                    keyAddress,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                                      .setBlockModes(BLOCK_MODE)
                                      .setKeySize(256)
                                      .setUserAuthenticationRequired(useAuthentication)
                                      .setIsStrongBoxBacked(true)
                                      .setInvalidatedByBiometricEnrollment(false)
                                      .setUserAuthenticationValidityDurationSeconds(AUTHENTICATION_DURATION_SECONDS)
                                      .setRandomizedEncryptionRequired(true)
                                      .setEncryptionPaddings(PADDING)
                                      .build());

            keyGenerator.generateKey();
        }
        catch (StrongBoxUnavailableException e)
        {
            return false;
        }
        catch (InvalidAlgorithmParameterException e)
        {
            return false;
        }

        return true;
    }

    private boolean tryInitTEEKey(KeyGenerator keyGenerator, String keyAddress, boolean useAuthentication)
    {
        try
        {
            keyGenerator.init(new KeyGenParameterSpec.Builder(
                    keyAddress,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(BLOCK_MODE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(useAuthentication)
                    .setInvalidatedByBiometricEnrollment(false)
                    .setUserAuthenticationValidityDurationSeconds(AUTHENTICATION_DURATION_SECONDS)
                    .setRandomizedEncryptionRequired(true)
                    .setEncryptionPaddings(PADDING)
                    .build());

        }
        catch (IllegalStateException | InvalidAlgorithmParameterException e)
        {
            //couldn't create the key because of no lock
            return false;
        }

        return true;
    }
    
    /**
     * Request authentication for a specific operation
     * @param activity The activity context
     * @param wallet The wallet context (can be null for operations not requiring wallet)
     * @param operation The operation requiring authentication
     */
    public void requestAuthentication(Activity activity, Wallet wallet, Operation operation)
    {
        this.activity = activity;
        this.currentWallet = wallet;
        this.discoveryCallback = null; // Clear any previous callback
        checkAuthentication(operation);
    }
    
    /**
     * Request authentication for discovery with callback
     * @param activity The activity context
     * @param wallet The wallet context
     * @param operation The operation requiring authentication
     * @param callback Callback to run when authentication completes successfully
     */
    public void requestAuthenticationWithCallback(Activity activity, Wallet wallet, Operation operation, Runnable callback)
    {
        this.activity = activity;
        this.currentWallet = wallet;
        if (operation == Operation.DISCOVER_ACCOUNTS)
        {
            this.discoveryCallback = callback;
        }
        checkAuthentication(operation);
    }

    private void checkAuthentication(Operation operation)
    {
        if (Utils.isRunningTest()) //running tests in debug build mode, we don't use key unlock
        {
            requireAuthentication = false;
            authenticatePass(operation);
            return;
        }

        String dialogTitle;
        switch (operation)
        {
            case UPGRADE_HD_KEY:
            case UPGRADE_KEYSTORE_KEY:
            case CREATE_PRIVATE_KEY:
            case CREATE_KEYSTORE_KEY:
            case IMPORT_HD_KEY:
            case CREATE_HD_KEY:
            case DERIVE_HD_ACCOUNT:
            case DISCOVER_ACCOUNTS:
                //always unlock for these conditions
                dialogTitle = context.getString(R.string.provide_authentication);
                break;
            case FETCH_MNEMONIC:
            case CHECK_AUTHENTICATION:
            case SIGN_DATA:
            default:
                dialogTitle = context.getString(R.string.unlock_private_key);
                //unlock may be optional here
                if (!requireAuthentication && (currentWallet.authLevel == TEE_NO_AUTHENTICATION || currentWallet.authLevel == STRONGBOX_NO_AUTHENTICATION)
                        && !requiresUnlock() && signCallback != null)
                {
                    signCallback.gotAuthorisation(true);
                    return;
                }
                break;
        }

        resetSigningDialog();

        signDialog = new SignTransactionDialog(context);
        signDialog.getAuthentication(this, activity, operation);
        requireAuthentication = false;
    }

    @Override
    public void completeAuthentication(Operation callbackId)
    {
        authenticatePass(callbackId);
    }

    @Override
    public void failedAuthentication(Operation taskCode)
    {
        authenticateFail("Authentication fail", AuthenticationFailType.PIN_FAILED, taskCode);
    }

    @Override
    public void authenticatePass(Operation operation)
    {
        //resume key operation
        switch (operation)
        {
            case CREATE_HD_KEY: //Note: not currently used: may be used if we create an HD key with authentication
                createHDKey();
                break;
            case FETCH_MNEMONIC:
                try
                {
                    callbackInterface.fetchMnemonic(unpackMnemonic());
                }
                catch (Exception e)
                {
                    keyFailure(e.getMessage());
                }
                break;
            case IMPORT_HD_KEY:
                importHDKey();
                break;
            case CHECK_AUTHENTICATION:
                signCallback.gotAuthorisation(true);
                break;
            case UPGRADE_HD_KEY:
            case UPGRADE_KEYSTORE_KEY:
                upgradeKey();
                break;
            case CREATE_KEYSTORE_KEY:
            case CREATE_PRIVATE_KEY:
                createPassword(operation);
                break;
            case DERIVE_HD_ACCOUNT:
                try
                {
                    performHDAccountDerivation();
                }
                catch (Exception e)
                {
                    keyFailure(e.getMessage());
                }
                break;
            case DISCOVER_ACCOUNTS:
                // Call the discovery callback if set (for biometric auth path)
                if (discoveryCallback != null)
                {
                    Runnable callback = discoveryCallback;
                    discoveryCallback = null;
                    callback.run();
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void authenticateFail(String fail, AuthenticationFailType failType, Operation callbackId)
    {
        switch (failType)
        {
            case AUTHENTICATION_DIALOG_CANCELLED: //user dialog cancel
                cancelAuthentication();
                break;
            case FINGERPRINT_ERROR_CANCELED:
                //called when user cancels the dialog
                return;
            case FINGERPRINT_NOT_VALIDATED:
                vibrate();
                activity.runOnUiThread(() -> Toast.makeText(context, R.string.fingerprint_authentication_failed, Toast.LENGTH_SHORT).show());
                break;
            case PIN_FAILED:
                vibrate();
                break;
            case DEVICE_NOT_SECURE:
                //Note:- allowing user to create a key with no auth-unlock ensures we should never get here
                //Handle some sort of edge condition where the user gets here.
                showInsecure(callbackId);
                break;
        }

        if (callbackId == UPGRADE_HD_KEY)
        {
            signCallback.gotAuthorisation(false);
        }

        if (activity == null || activity.isDestroyed())
        {
            cancelAuthentication();
        }
    }

    @Override
    public void legacyAuthRequired(Operation callbackId, String dialogTitle, String desc)
    {
//        signDialog = new SignTransactionDialog2(activity, callbackId, dialogTitle, desc);
//        signDialog.setCanceledOnTouchOutside(false);
//        signDialog.setCancelListener(v -> {
//            authenticateFail("Cancelled", AuthenticationFailType.AUTHENTICATION_DIALOG_CANCELLED, callbackId);
//        });
//        signDialog.setOnDismissListener(v -> {
//            signDialog = null;
//        });
//        signDialog.show();
//        signDialog.getLegacyAuthentication(this);
//        requireAuthentication = false;
    }

    private void keyFailure(String message)
    {
        if (message == null || message.length() == 0 || !AuthorisationFailMessage(message))
        {
            if (callbackInterface != null)
                callbackInterface.keyFailure(message);
            else if (signCallback != null)
                signCallback.gotAuthorisation(false);
            else
                AuthorisationFailMessage(message);
        }
    }

    private void cancelAuthentication()
    {
        if (signCallback != null)
            signCallback.cancelAuthentication();
        else if (callbackInterface != null)
            callbackInterface.cancelAuthentication();
    }

    private boolean AuthorisationFailMessage(String message)
    {
        if (alertDialog != null && alertDialog.isShowing())
            activity.runOnUiThread(() -> alertDialog.dismiss());
        if (activity == null || activity.isDestroyed())
            return false;

        activity.runOnUiThread(() -> {
            alertDialog = new AWalletAlertDialog(activity);
            alertDialog.setIcon(AWalletAlertDialog.ERROR);
            alertDialog.setTitle(R.string.key_error);
            alertDialog.setMessage(message);
            alertDialog.setButtonText(R.string.action_continue);
            alertDialog.setCanceledOnTouchOutside(true);
            alertDialog.setButtonListener(v -> {
                keyFailure("");
                alertDialog.dismiss();
            });
            alertDialog.setOnCancelListener(v -> {
                keyFailure("");
                cancelAuthentication();
            });
            alertDialog.show();
        });

        return true;
    }

    /**
     * Current behaviour: Allow user to create unsecured key
     *
     * @param callbackId
     */
    private void showInsecure(Operation callbackId)
    {
        //only show the 'not secure' message on certain occasions. Otherwise just pass through.
        switch (callbackId)
        {
            case CREATE_HD_KEY:
            case IMPORT_HD_KEY:
            case CREATE_PRIVATE_KEY:
            case CREATE_KEYSTORE_KEY:
            case UPGRADE_KEYSTORE_KEY:
            case UPGRADE_HD_KEY:
                //warn user their phone is insecure
                break;
            default:
                //proceed to use key, don't show unlocked warning
                authenticatePass(callbackId);
                return;
        }

        AWalletAlertDialog dialog = new AWalletAlertDialog(activity);
        dialog.setIcon(AWalletAlertDialog.ERROR);
        dialog.setTitle(R.string.device_insecure);
        dialog.setMessage(R.string.device_not_secure_warning);
        dialog.setButtonText(R.string.action_continue);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setButtonListener(v -> {
            //proceed with operation
            switch (callbackId)
            {
                case UPGRADE_KEYSTORE_KEY:
                case UPGRADE_HD_KEY:
                    //dismiss sign dialog & cancel authentication
                    /*if (signDialog != null && signDialog.isShowing())
                        signDialog.dismiss();*/
                    cancelAuthentication();
                    break;
                default:
                    authenticatePass(callbackId);
                    break;
            }
            dialog.dismiss();
        });
        dialog.show();
    }

    /**
     * Only ever called after authentication event
     */
    private void createPassword(Operation operation)
    {
        boolean requireAuthentication = !Utils.isRunningTest();

        //generate password
        byte[] newPassword = new byte[256];
        SecureRandom random;
        try
        {
            //attempt to use superior source of randomness
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            {
                random = SecureRandom.getInstanceStrong(); //this can throw a NoSuchAlgorithmException
            }
            else
            {
                random = new SecureRandom();
            }
        }
        catch (NoSuchAlgorithmException e)
        {
            random = new SecureRandom();
        }

        random.nextBytes(newPassword);

        boolean success = storeEncryptedBytes(newPassword, requireAuthentication, currentWallet.address);  //because we'll now only ever be importing keystore, always create with Auth if possible

        // Verify the password was stored correctly by reading it back
        if (success)
        {
            success = verifyStoredPassword(currentWallet.address, newPassword);
        }

        if (!success)
        {
            AuthorisationFailMessage(context.getString(R.string.please_enable_security));
        }
        else
        {
            switch (operation)
            {
                case CREATE_KEYSTORE_KEY:
                    importCallback.walletValidated(new String(newPassword), KeyEncodingType.KEYSTORE_KEY, authLevel);
                    break;
                case CREATE_PRIVATE_KEY:
                    importCallback.walletValidated(new String(newPassword), KeyEncodingType.RAW_HEX_KEY, authLevel);
                    break;
            }
        }
    }
    
    /**
     * Verify that a stored password can be read back correctly
     * This ensures the key storage was successful and the password is recoverable
     * for Keystore and Private Key imports
     */
    private boolean verifyStoredPassword(String address, byte[] expectedPassword)
    {
        try
        {
            // Check if files exist
            String encryptedFilePath = getFilePath(context, address);
            String ivFilePath = getFilePath(context, address + "iv");
            
            File encryptedFile = new File(encryptedFilePath);
            File ivFile = new File(ivFilePath);
            
            if (!encryptedFile.exists() || !ivFile.exists())
            {
                Timber.tag(TAG).e("Password verification failed: files don't exist for address %s", address);
                return false;
            }
            
            // Verify KeyStore contains the key
            if (!hasKeystore(address))
            {
                Timber.tag(TAG).e("Password verification failed: KeyStore doesn't contain key for address %s", address);
                return false;
            }
            
            // Try to decrypt and verify the password matches
            Pair<KeyExceptionType, String> testResult = testCipher(address, CIPHER_ALGORITHM);
            if (testResult.first == KeyExceptionType.SUCCESSFUL_DECODE)
            {
                // Verify the decrypted password matches what we stored
                String storedPassword = testResult.second;
                String expectedPasswordStr = new String(expectedPassword);
                if (expectedPasswordStr.equals(storedPassword))
                {
                    Timber.tag(TAG).d("Password verification successful for address %s", address);
                    return true;
                }
                else
                {
                    Timber.tag(TAG).e("Password verification failed: password mismatch for address %s", address);
                    return false;
                }
            }
            else if (testResult.first == KeyExceptionType.REQUIRES_AUTH)
            {
                // Key requires authentication to decrypt - key exists, so return true
                Timber.tag(TAG).d("Password verification: auth required for address %s (key exists)", address);
                return true;
            }
            else
            {
                Timber.tag(TAG).e("Password verification failed: cipher test returned %s for address %s", testResult.first, address);
                return false;
            }
        }
        catch (Exception e)
        {
            Timber.tag(TAG).e(e, "Password verification exception for address %s", address);
            return false;
        }
    }

    private synchronized SignatureFromKey signWithKeystore(byte[] transactionBytes)
    {
        //1. get password from store
        //2. construct credentials
        //3. sign
        SignatureFromKey returnSig = new SignatureFromKey();

        try
        {
            String password = "";
            switch (currentWallet.type)
            {
                default:
                case KEYSTORE:
                    password = unpackMnemonic();
                    break;
                case KEYSTORE_LEGACY:
                    password = new String(getLegacyPassword(context, currentWallet.address));
                    break;
            }

            File keyFolder = new File(context.getFilesDir(), KEYSTORE_FOLDER);
            Credentials credentials = KeystoreAccountService.getCredentials(keyFolder, currentWallet.address, password);
            Sign.SignatureData signatureData = Sign.signMessage(
                    transactionBytes, credentials.getEcKeyPair());
            returnSig.signature = bytesFromSignature(signatureData);
            returnSig.sigType = SignatureReturnType.SIGNATURE_GENERATED; //only reach here if signature was generated correctly
        }
        catch (ServiceErrorException e)
        {
            //Legacy keystore error
            if (!BuildConfig.DEBUG) analyticsService.recordException(e);
            returnSig.failMessage = e.getMessage();
            e.printStackTrace();
        }
        catch (Exception e)
        {
            returnSig.failMessage = e.getMessage();
            e.printStackTrace();
        }

        return returnSig;
    }

    /*
            Utility methods
     */

    public static byte[] readBytesFromFile(String path)
    {
        byte[] bytes = null;
        File file = new File(path);
        try (FileInputStream fin = new FileInputStream(file))
        {
            bytes = readBytesFromStream(fin);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
        return bytes;
    }

    public static byte[] readBytesFromStream(InputStream in) throws IOException
    {
        ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();
        int bufferSize = 2048;
        byte[] buffer = new byte[bufferSize];

        int len;
        while ((len = in.read(buffer)) != -1)
        {
            byteBuffer.write(buffer, 0, len);
        }

        byteBuffer.close();
        return byteBuffer.toByteArray();
    }

    /**
     * Finds matching key in keystore regardless of case
     *
     * @param keyAddress
     * @return
     */
    private String findMatchingAddrInKeyStore(String keyAddress)
    {
        try
        {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            Enumeration<String> keys = keyStore.aliases();

            while (keys.hasMoreElements())
            {
                String thisKey = keys.nextElement();
                if (keyAddress.equalsIgnoreCase(thisKey))
                {
                    return thisKey;
                }
            }
        }
        catch (Exception e)
        {
            Timber.e(e);
        }

        return keyAddress;
    }

    public synchronized static String getFilePath(Context context, String fileName)
    {
        //check for matching file
        File check = new File(context.getFilesDir(), fileName);
        if (check.exists())
        {
            return check.getAbsolutePath(); //quick return
        }
        else
        {
            //find matching file, ignoring case
            File[] files = context.getFilesDir().listFiles();
            for (File checkFile : files)
            {
                if (checkFile.getName().equalsIgnoreCase(fileName))
                {
                    return checkFile.getAbsolutePath();
                }
            }
        }

        return check.getAbsolutePath(); //Should never get here
    }

    private boolean writeBytesToFile(String path, byte[] data)
    {
        File file = new File(path);
        try (FileOutputStream fos = new FileOutputStream(file))
        {
            fos.write(data);
        }
        catch (IOException e)
        {
            Timber.d(e, "Exception while writing file ");
            return false;
        }

        return true;
    }

    /**
     * Delete all traces of the key in Android keystore, encrypted bytes and iv file in private data area
     * @param keyAddress
     */
    synchronized void deleteKey(String keyAddress)
    {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            String matchingAddr = findMatchingAddrInKeyStore(keyAddress);
            if (keyStore.containsAlias(matchingAddr)) keyStore.deleteEntry(matchingAddr);
            File encryptedKeyBytes = new File(getFilePath(context, matchingAddr));
            File encryptedBytesFileIV = new File(getFilePath(context, matchingAddr + "iv"));
            if (encryptedKeyBytes.exists()) encryptedKeyBytes.delete();
            if (encryptedBytesFileIV.exists()) encryptedBytesFileIV.delete();
            deleteAccount(matchingAddr);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void deleteAccount(String address) throws Exception
    {
        String cleanedAddr = Numeric.cleanHexPrefix(address).toLowerCase();
            deleteAccountFiles(cleanedAddr);

            //Now delete database files (ie tokens, transactions and Tokenscript data for account)
            File[] contents = context.getFilesDir().listFiles();
            if (contents != null)
            {
                for (File f : contents)
                {
                    String fileName = f.getName().toLowerCase();
                    if (fileName.contains(cleanedAddr.toLowerCase()))
                    {
                        deleteRecursive(f);
                    }
                }
            }
    }

    private void deleteAccountFiles(String address) throws Exception
    {
        String cleanedAddr = Numeric.cleanHexPrefix(address);

        File keyFolder = new File(context.getFilesDir(), KEYSTORE_FOLDER);
        File[] contents = keyFolder.listFiles();
        if (contents != null)
        {
            for (File f : contents)
            {
                if (f.getName().contains(cleanedAddr))
                {
                    f.delete();
                }
            }
        }
    }

    private void deleteRecursive(File fp)
    {
        if (fp.isDirectory())
        {
            File[] contents = fp.listFiles();
            if (contents != null)
            {
                for (File child : contents)
                    deleteRecursive(child);
            }
        }

        fp.delete();
    }

    private void checkSecurity()
    {
        if (securityStatus == SecurityStatus.NOT_CHECKED)
        {
            getMaxSecurityKeyGenerator(ZERO_ADDRESS, false);
            switch (authLevel)
            {
                case NOT_SET:
                    securityStatus = SecurityStatus.HAS_NO_TEE;
                    break;
                case TEE_NO_AUTHENTICATION:
                case TEE_AUTHENTICATION:
                    securityStatus = SecurityStatus.HAS_TEE;
                    break;
                case STRONGBOX_NO_AUTHENTICATION:
                case STRONGBOX_AUTHENTICATION:
                    securityStatus = SecurityStatus.HAS_STRONGBOX;
                    break;
            }
        }
    }

    private boolean requiresUnlock()
    {
        try
        {
            unpackMnemonic();
        }
        catch (Exception e)
        {
            return true;
        }

        return false;
    }

    public boolean deviceIsSecured()
    {
        KeyguardManager keyguardManager = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguardManager == null) return false;
        else return keyguardManager.isDeviceSecure();
    }
    
    private boolean deviceIsLocked()
    {
        return deviceIsSecured();
    }

    private void vibrate()
    {
        Vibrator vb = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vb != null && vb.hasVibrator())
        {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                VibrationEffect vibe = VibrationEffect.createOneShot(200, DEFAULT_AMPLITUDE);
                vb.vibrate(vibe);
            }
            else
            {
                //noinspection deprecation
                vb.vibrate(200);
            }
        }
    }

    public boolean hasKeystore(String walletAddress)
    {
        try
        {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            String matchingAddr = findMatchingAddrInKeyStore(walletAddress);
            boolean hasAlias = keyStore.containsAlias(matchingAddr);
            
            if (hasAlias)
            {
                return true;
            }
            
            // KeyStore alias not found - check if encrypted files exist
            // This can happen on emulators where KeyStore entries get lost
            String encryptedFilePath = getFilePath(context, walletAddress);
            String ivFilePath = getFilePath(context, walletAddress + "iv");
            java.io.File encryptedFile = new java.io.File(encryptedFilePath);
            java.io.File ivFile = new java.io.File(ivFilePath);
            
            if (encryptedFile.exists() && ivFile.exists())
            {
                Timber.tag(TAG).w("KeyStore alias not found but encrypted files exist for %s - possible KeyStore corruption", walletAddress);
                // Files exist but KeyStore entry is gone - this is a KeyStore corruption issue
                // We can't recover without re-creating the key
                return false;
            }
            
            return false;
        }
        catch (KeyStoreException|NoSuchAlgorithmException|CertificateException|IOException e)
        {
            Timber.e(e);
        }

        return false;
    }
    
    /**
     * Comprehensive verification of key storage
     * Checks:
     * 1. KeyStore contains the key alias
     * 2. Encrypted data file exists
     * 3. IV file exists
     * 
     * This is more thorough than hasKeystore() and helps detect partial storage failures
     */
    public boolean verifyKeyStorageComplete(String walletAddress)
    {
        try
        {
            // Check KeyStore
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            String matchingAddr = findMatchingAddrInKeyStore(walletAddress);
            
            if (!keyStore.containsAlias(matchingAddr))
            {
                Timber.tag(TAG).w("Key verification: KeyStore alias not found for %s", walletAddress);
                return false;
            }
            
            // Check encrypted data file
            String encryptedFilePath = getFilePath(context, matchingAddr);
            java.io.File encryptedFile = new java.io.File(encryptedFilePath);
            if (!encryptedFile.exists())
            {
                Timber.tag(TAG).w("Key verification: Encrypted file not found for %s", walletAddress);
                return false;
            }
            
            // Check IV file
            String ivFilePath = getFilePath(context, matchingAddr + "iv");
            java.io.File ivFile = new java.io.File(ivFilePath);
            if (!ivFile.exists())
            {
                Timber.tag(TAG).w("Key verification: IV file not found for %s", walletAddress);
                return false;
            }
            
            Timber.tag(TAG).d("Key verification successful for %s", walletAddress);
            return true;
        }
        catch (KeyStoreException|NoSuchAlgorithmException|CertificateException|IOException e)
        {
            Timber.tag(TAG).e(e, "Key verification error for %s", walletAddress);
        }

        return false;
    }

    static boolean hasStrongbox()
    {
        return securityStatus == SecurityStatus.HAS_STRONGBOX;
    }

    public enum KeyExceptionType
    {
        UNKNOWN, REQUIRES_AUTH, INVALID_CIPHER, SUCCESSFUL_DECODE, IV_NOT_FOUND, ENCRYPTED_FILE_NOT_FOUND
    }
}
