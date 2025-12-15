package com.ramapay.app.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Vibrator;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.ramapay.app.C;
import com.ramapay.app.R;
import com.ramapay.app.entity.PaymentCategory;
import com.ramapay.app.entity.Wallet;
import com.ramapay.app.entity.tokens.Token;
import com.ramapay.app.repository.entity.RealmPosInvoice;
import com.ramapay.app.ui.QRScanning.DisplayUtils;
import com.ramapay.app.util.QRUtils;
import com.ramapay.app.viewmodel.PosViewModel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

import dagger.hilt.android.AndroidEntryPoint;
import timber.log.Timber;

/**
 * Point of Sale Activity
 * Allows merchants to create payment requests with fiat amount entry
 * and QR code generation for customers to scan
 */
@AndroidEntryPoint
public class PosActivity extends BaseActivity
{
    private PosViewModel viewModel;

    // UI Elements - Amount Input
    private MaterialCardView cardAmountInput;
    private MaterialButton buttonCurrency;
    private TextInputEditText editFiatAmount;
    private LinearLayout layoutTokenSelector;
    private ImageView imageToken;
    private TextView textTokenSymbol;
    private TextView textTokenName;
    private TextView textCryptoAmount;

    // UI Elements - Category and Remark
    private LinearLayout layoutCategorySelector;
    private TextView textCategory;
    private TextInputEditText inputRemark;
    private String selectedCategoryId = "other";

    // UI Elements - QR Code
    private MaterialCardView cardQrCode;
    private MaterialCardView cardTokenSelection;
    private MaterialCardView cardPaymentDetails;
    private ImageView imageQrCode;
    private TextView textQrFiatAmount;
    private TextView textQrCryptoAmount;
    private TextView textInvoiceId;
    private LinearLayout layoutWaiting;
    private TextView textWaitingStatus;
    private TextView textTimer;
    private TextView textReceivingAddress;
    private android.widget.ImageButton buttonCopyAddress;
    private android.widget.ImageButton buttonShareAddress;

    // UI Elements - Payment Success
    private MaterialCardView cardPaymentSuccess;
    private TextView textSuccessAmount;
    private TextView textSuccessCrypto;
    private TextView textSuccessTx;

    // UI Elements - Buttons
    private MaterialButton buttonGenerateQr;
    private MaterialButton buttonNewPayment;
    private MaterialButton buttonCancel;

    private int screenWidth;
    private String currentWalletAddress;
    private Wallet currentWallet;
    private RealmPosInvoice currentInvoice;

    public static Intent createIntent(Context context, Wallet wallet)
    {
        Intent intent = new Intent(context, PosActivity.class);
        intent.putExtra(C.Key.WALLET, wallet);
        return intent;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pos);

        screenWidth = Math.min((int) ((float) DisplayUtils.getScreenResolution(this).x * 0.7f), 800);

        toolbar();
        setTitle(getString(R.string.pos_title));

        initViews();
        initViewModel();
        setupListeners();
    }

    private void initViews()
    {
        // Amount Input Section
        cardAmountInput = findViewById(R.id.card_amount_input);
        buttonCurrency = findViewById(R.id.button_currency);
        editFiatAmount = findViewById(R.id.edit_fiat_amount);
        
        // Token Selection
        cardTokenSelection = findViewById(R.id.card_token_selection);
        layoutTokenSelector = findViewById(R.id.layout_token_selector);
        imageToken = findViewById(R.id.image_token);
        textTokenSymbol = findViewById(R.id.text_token_symbol);
        textTokenName = findViewById(R.id.text_token_name);
        textCryptoAmount = findViewById(R.id.text_crypto_amount);

        // Category and Remark
        cardPaymentDetails = findViewById(R.id.card_payment_details);
        layoutCategorySelector = findViewById(R.id.layout_category_selector);
        textCategory = findViewById(R.id.text_category);
        inputRemark = findViewById(R.id.input_remark);

        // QR Code Section
        cardQrCode = findViewById(R.id.card_qr_code);
        imageQrCode = findViewById(R.id.image_qr_code);
        textQrFiatAmount = findViewById(R.id.text_qr_fiat_amount);
        textQrCryptoAmount = findViewById(R.id.text_qr_crypto_amount);
        textInvoiceId = findViewById(R.id.text_invoice_id);
        layoutWaiting = findViewById(R.id.layout_waiting);
        textWaitingStatus = findViewById(R.id.text_waiting_status);
        textTimer = findViewById(R.id.text_timer);
        textReceivingAddress = findViewById(R.id.text_receiving_address);
        buttonCopyAddress = findViewById(R.id.button_copy_address);
        buttonShareAddress = findViewById(R.id.button_share_address);

        // Payment Success Section
        cardPaymentSuccess = findViewById(R.id.card_payment_success);
        textSuccessAmount = findViewById(R.id.text_success_amount);
        textSuccessCrypto = findViewById(R.id.text_success_crypto);
        textSuccessTx = findViewById(R.id.text_success_tx);

        // Buttons
        buttonGenerateQr = findViewById(R.id.button_generate_qr);
        buttonNewPayment = findViewById(R.id.button_new_payment);
        buttonCancel = findViewById(R.id.button_cancel);
    }

    private void initViewModel()
    {
        viewModel = new ViewModelProvider(this).get(PosViewModel.class);

        viewModel.defaultWallet().observe(this, this::onWalletLoaded);
        viewModel.tokens().observe(this, this::onTokensLoaded);
        viewModel.cryptoAmount().observe(this, this::onCryptoAmountCalculated);
        viewModel.exchangeRate().observe(this, this::onExchangeRateLoaded);
        viewModel.currentInvoice().observe(this, this::onInvoiceCreated);
        viewModel.paymentReceived().observe(this, this::onPaymentReceived);
        viewModel.remainingTime().observe(this, this::onRemainingTimeUpdate);
        viewModel.paymentTimeout().observe(this, this::onPaymentTimeout);

        viewModel.prepare();

        // Set initial currency
        updateCurrencyButton(viewModel.getSelectedCurrency());
    }

    private void setupListeners()
    {
        // Currency selection
        buttonCurrency.setOnClickListener(v -> showCurrencyPicker());

        // Fiat amount input
        editFiatAmount.addTextChangedListener(new TextWatcher()
        {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s)
            {
                viewModel.calculateCryptoAmount(s.toString());
            }
        });

        // Token selection
        layoutTokenSelector.setOnClickListener(v -> showTokenPicker());

        // Category selection
        layoutCategorySelector.setOnClickListener(v -> showCategoryPicker());

        // Generate QR button
        buttonGenerateQr.setOnClickListener(v -> generatePaymentQr());

        // New payment button
        buttonNewPayment.setOnClickListener(v -> resetForNewPayment());

        // Cancel button
        buttonCancel.setOnClickListener(v -> cancelCurrentPayment());
        
        // Copy address button
        buttonCopyAddress.setOnClickListener(v -> copyAddressToClipboard());
        
        // Share address button
        buttonShareAddress.setOnClickListener(v -> sharePaymentDetails());
    }
    
    private void copyAddressToClipboard()
    {
        if (currentWalletAddress != null && !currentWalletAddress.isEmpty())
        {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) 
                    getSystemService(Context.CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("Address", currentWalletAddress);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, R.string.pos_address_copied, Toast.LENGTH_SHORT).show();
        }
    }
    
    private void sharePaymentDetails()
    {
        if (currentInvoice == null || currentWalletAddress == null) return;
        
        String currencySymbol = viewModel.getCurrencySymbol(currentInvoice.getFiatCurrency());
        BigDecimal cryptoDisplay = new BigDecimal(currentInvoice.getCryptoAmount())
                .divide(BigDecimal.TEN.pow(currentInvoice.getTokenDecimals()), 6, RoundingMode.HALF_UP);
        
        String shareText = "Payment Request\n\n" +
                "Amount: " + currencySymbol + currentInvoice.getFiatAmount() + "\n" +
                "Crypto: " + cryptoDisplay.stripTrailingZeros().toPlainString() + " " + currentInvoice.getTokenSymbol() + "\n\n" +
                "Send to address:\n" + currentWalletAddress + "\n\n" +
                "Network: Ramestta (Chain ID 1370)";
        
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Payment Request");
        shareIntent.putExtra(Intent.EXTRA_TEXT, shareText);
        startActivity(Intent.createChooser(shareIntent, "Share via"));
    }

    private void showCategoryPicker()
    {
        List<PaymentCategory> categories = PaymentCategory.getAllCategories();
        String[] categoryNames = new String[categories.size()];
        for (int i = 0; i < categories.size(); i++)
        {
            categoryNames[i] = categories.get(i).getDisplayName();
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.pos_select_category)
                .setItems(categoryNames, (dialog, which) -> {
                    PaymentCategory selected = categories.get(which);
                    selectedCategoryId = selected.getId();
                    textCategory.setText(selected.getDisplayName());
                })
                .show();
    }

    private void onWalletLoaded(Wallet wallet)
    {
        Timber.d("Wallet loaded: %s", wallet.address);
        currentWallet = wallet;
        currentWalletAddress = wallet.address;
        
        // Update receiving address display
        if (textReceivingAddress != null && currentWalletAddress != null)
        {
            textReceivingAddress.setText(currentWalletAddress);
        }
    }

    private void onTokensLoaded(List<Token> tokens)
    {
        if (tokens != null && !tokens.isEmpty())
        {
            Token firstToken = tokens.get(0);
            updateTokenDisplay(firstToken);
        }
    }

    private void onExchangeRateLoaded(Double rate)
    {
        // Recalculate if amount is entered
        String amountStr = editFiatAmount.getText() != null ? editFiatAmount.getText().toString() : "";
        if (!TextUtils.isEmpty(amountStr))
        {
            viewModel.calculateCryptoAmount(amountStr);
        }
    }

    private void onCryptoAmountCalculated(BigDecimal amount)
    {
        if (amount != null && viewModel.getSelectedToken() != null)
        {
            String formatted = amount.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
            String display = formatted + " " + viewModel.getSelectedToken().getSymbol();
            textCryptoAmount.setText(display);
        }
        else
        {
            textCryptoAmount.setText("0.00 " + (viewModel.getSelectedToken() != null ? 
                    viewModel.getSelectedToken().getSymbol() : "RAMA"));
        }
    }

    private void onInvoiceCreated(RealmPosInvoice invoice)
    {
        if (invoice == null) return;

        currentInvoice = invoice;

        // Generate QR code
        String paymentUri = viewModel.generatePaymentUri(invoice);
        Timber.d("Payment URI: %s", paymentUri);

        imageQrCode.setImageBitmap(QRUtils.createQRImage(this, paymentUri, screenWidth));

        // Update QR display
        String currencySymbol = viewModel.getCurrencySymbol(invoice.getFiatCurrency());
        textQrFiatAmount.setText(currencySymbol + invoice.getFiatAmount());

        BigDecimal cryptoDisplay = new BigDecimal(invoice.getCryptoAmount())
                .divide(BigDecimal.TEN.pow(invoice.getTokenDecimals()), 6, RoundingMode.HALF_UP);
        textQrCryptoAmount.setText("≈ " + cryptoDisplay.stripTrailingZeros().toPlainString() 
                + " " + invoice.getTokenSymbol());

        textInvoiceId.setText(getString(R.string.pos_invoice, invoice.getInvoiceId().substring(0, 16) + "..."));

        // Show QR card, hide input
        showQrCodeScreen();
        
        // Start payment monitoring with 5 minute timeout
        viewModel.startPaymentMonitoring(invoice);
    }

    private void onRemainingTimeUpdate(Long remainingMs)
    {
        if (remainingMs == null || textTimer == null) return;
        
        long minutes = remainingMs / 60000;
        long seconds = (remainingMs % 60000) / 1000;
        String timeStr = String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
        textTimer.setText(timeStr);
        
        // Change color when less than 1 minute
        if (remainingMs < 60000)
        {
            textTimer.setTextColor(getResources().getColor(R.color.negative, null));
        }
        else
        {
            textTimer.setTextColor(getResources().getColor(R.color.brand, null));
        }
    }

    private void onPaymentTimeout(Boolean isTimeout)
    {
        if (isTimeout == null || !isTimeout) return;
        
        // Show timeout message
        if (textWaitingStatus != null)
        {
            textWaitingStatus.setText(R.string.pos_payment_timeout);
        }
        
        Toast.makeText(this, R.string.pos_payment_timeout, Toast.LENGTH_LONG).show();
        
        // Auto-close the QR popup after 3 seconds and return to input screen
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            showInputScreen();
            // Clear the amount input for next transaction
            if (editFiatAmount != null)
            {
                editFiatAmount.setText("");
            }
            currentInvoice = null;
        }, 3000);
    }

    private void onPaymentReceived(RealmPosInvoice paidInvoice)
    {
        if (paidInvoice == null) return;

        // Play success sound and vibrate
        playSuccessSound();
        vibrate();

        // Update success display
        String currencySymbol = viewModel.getCurrencySymbol(paidInvoice.getFiatCurrency());
        textSuccessAmount.setText(currencySymbol + paidInvoice.getFiatAmount());

        BigDecimal cryptoDisplay = new BigDecimal(paidInvoice.getCryptoAmount())
                .divide(BigDecimal.TEN.pow(paidInvoice.getTokenDecimals()), 6, RoundingMode.HALF_UP);
        textSuccessCrypto.setText(cryptoDisplay.stripTrailingZeros().toPlainString() 
                + " " + paidInvoice.getTokenSymbol());

        String txHash = paidInvoice.getTxHash();
        if (!TextUtils.isEmpty(txHash))
        {
            textSuccessTx.setText("TX: " + txHash.substring(0, 10) + "..." + txHash.substring(txHash.length() - 8));
        }

        // Show success card
        showPaymentSuccessScreen();
    }

    private void generatePaymentQr()
    {
        String amountStr = editFiatAmount.getText() != null ? editFiatAmount.getText().toString() : "";

        if (TextUtils.isEmpty(amountStr))
        {
            Toast.makeText(this, R.string.pos_enter_valid_amount, Toast.LENGTH_SHORT).show();
            return;
        }

        try
        {
            BigDecimal fiatAmount = new BigDecimal(amountStr);
            if (fiatAmount.compareTo(BigDecimal.ZERO) <= 0)
            {
                Toast.makeText(this, R.string.pos_enter_valid_amount, Toast.LENGTH_SHORT).show();
                return;
            }

            BigDecimal cryptoAmt = viewModel.convertFiatToCrypto(fiatAmount);
            if (cryptoAmt.compareTo(BigDecimal.ZERO) <= 0)
            {
                Toast.makeText(this, R.string.pos_no_exchange_rate, Toast.LENGTH_SHORT).show();
                return;
            }

            // Get category and remark
            String remark = inputRemark.getText() != null ? inputRemark.getText().toString().trim() : "";

            // Create invoice with category and remark
            viewModel.createInvoice(fiatAmount, cryptoAmt, selectedCategoryId, remark);
        }
        catch (NumberFormatException e)
        {
            Toast.makeText(this, R.string.pos_enter_valid_amount, Toast.LENGTH_SHORT).show();
        }
    }

    private void showCurrencyPicker()
    {
        String[] currencies = PosViewModel.SUPPORTED_CURRENCIES;
        String[] displayCurrencies = new String[currencies.length];

        for (int i = 0; i < currencies.length; i++)
        {
            displayCurrencies[i] = viewModel.getCurrencySymbol(currencies[i]) + " " + currencies[i];
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.pos_select_currency)
                .setItems(displayCurrencies, (dialog, which) -> {
                    String selected = currencies[which];
                    viewModel.setSelectedCurrency(selected);
                    updateCurrencyButton(selected);
                })
                .show();
    }

    private void showTokenPicker()
    {
        List<Token> tokenList = viewModel.tokens().getValue();
        if (tokenList == null || tokenList.isEmpty())
        {
            Toast.makeText(this, "No tokens available", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] tokenNames = new String[tokenList.size()];
        for (int i = 0; i < tokenList.size(); i++)
        {
            Token token = tokenList.get(i);
            tokenNames[i] = token.getSymbol() + " - " + token.getName();
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.pos_select_token)
                .setItems(tokenNames, (dialog, which) -> {
                    Token selected = tokenList.get(which);
                    viewModel.setSelectedToken(selected);
                    updateTokenDisplay(selected);

                    // Recalculate crypto amount
                    String amountStr = editFiatAmount.getText() != null ? 
                            editFiatAmount.getText().toString() : "";
                    viewModel.calculateCryptoAmount(amountStr);
                })
                .show();
    }

    private void updateCurrencyButton(String currency)
    {
        buttonCurrency.setText(currency);
    }

    private void updateTokenDisplay(Token token)
    {
        textTokenSymbol.setText(token.getSymbol());
        textTokenName.setText(token.getName());
        // TODO: Load token icon
        // imageToken.setImageResource(...)
    }

    private void showQrCodeScreen()
    {
        cardAmountInput.setVisibility(View.GONE);
        cardTokenSelection.setVisibility(View.GONE);
        cardPaymentDetails.setVisibility(View.GONE);
        buttonGenerateQr.setVisibility(View.GONE);

        cardQrCode.setVisibility(View.VISIBLE);
        buttonCancel.setVisibility(View.VISIBLE);
        layoutWaiting.setVisibility(View.VISIBLE);

        cardPaymentSuccess.setVisibility(View.GONE);
        buttonNewPayment.setVisibility(View.GONE);
    }

    private void showPaymentSuccessScreen()
    {
        cardQrCode.setVisibility(View.GONE);
        layoutWaiting.setVisibility(View.GONE);
        buttonCancel.setVisibility(View.GONE);

        cardPaymentSuccess.setVisibility(View.VISIBLE);
        buttonNewPayment.setVisibility(View.VISIBLE);
    }

    private void showInputScreen()
    {
        cardAmountInput.setVisibility(View.VISIBLE);
        cardTokenSelection.setVisibility(View.VISIBLE);
        cardPaymentDetails.setVisibility(View.VISIBLE);
        buttonGenerateQr.setVisibility(View.VISIBLE);

        cardQrCode.setVisibility(View.GONE);
        cardPaymentSuccess.setVisibility(View.GONE);
        buttonNewPayment.setVisibility(View.GONE);
        buttonCancel.setVisibility(View.GONE);
    }

    private void resetForNewPayment()
    {
        viewModel.stopPaymentMonitoring();
        currentInvoice = null;
        editFiatAmount.setText("");
        inputRemark.setText("");
        selectedCategoryId = "other";
        textCategory.setText("📋 Select Category");
        textCryptoAmount.setText("0.00 " + (viewModel.getSelectedToken() != null ? 
                viewModel.getSelectedToken().getSymbol() : "RAMA"));
        
        // Reset timer display
        if (textTimer != null) textTimer.setText("");
        if (textWaitingStatus != null) textWaitingStatus.setText(R.string.pos_waiting_for_payment);
        
        showInputScreen();
    }

    private void cancelCurrentPayment()
    {
        viewModel.stopPaymentMonitoring();
        if (currentInvoice != null)
        {
            viewModel.cancelInvoice(currentInvoice.getInvoiceId());
        }
        resetForNewPayment();
    }

    private void playSuccessSound()
    {
        try
        {
            // Use system notification sound
            android.media.RingtoneManager.getRingtone(
                    getApplicationContext(),
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            ).play();
        }
        catch (Exception e)
        {
            Timber.e(e, "Failed to play success sound");
        }
    }

    private void vibrate()
    {
        try
        {
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator())
            {
                vibrator.vibrate(200);
            }
        }
        catch (Exception e)
        {
            Timber.e(e, "Failed to vibrate");
        }
    }

    @Override
    public void handleBackPressed()
    {
        if (cardQrCode.getVisibility() == View.VISIBLE)
        {
            // If showing QR, ask to cancel
            new AlertDialog.Builder(this)
                    .setTitle(R.string.action_cancel)
                    .setMessage("Cancel this payment request?")
                    .setPositiveButton(R.string.yes_continue, (dialog, which) -> cancelCurrentPayment())
                    .setNegativeButton(R.string.action_cancel, null)
                    .show();
        }
        else
        {
            finish();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu)
    {
        getMenuInflater().inflate(R.menu.menu_pos, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item)
    {
        int id = item.getItemId();
        
        if (id == R.id.action_history)
        {
            startActivity(new Intent(this, PosHistoryActivity.class));
            return true;
        }
        else if (id == R.id.action_business_profile)
        {
            Intent intent = new Intent(this, BusinessProfileActivity.class);
            intent.putExtra("wallet", currentWallet);
            startActivity(intent);
            return true;
        }
        
        return super.onOptionsItemSelected(item);
    }
}
