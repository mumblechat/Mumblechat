package com.ramapay.app.widget;

import android.app.AlertDialog;
import android.content.Context;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.LayoutRes;

import com.ramapay.app.R;


public class AddWalletView extends FrameLayout implements View.OnClickListener {
    private OnNewWalletClickListener onNewWalletClickListener;
    private OnImportWalletClickListener onImportWalletClickListener;
    private OnWatchWalletClickListener onWatchWalletClickListener;
    private OnCloseActionListener onCloseActionListener;
    private OnHardwareCardActionListener onHardwareCardClickListener;
    private OnAddAccountClickListener onAddAccountClickListener;
    private View addAccountAction;

    public AddWalletView(Context context) {
        this(context, R.layout.layout_dialog_add_account);
    }

    public AddWalletView(Context context, @LayoutRes int layoutId) {
        super(context);

        init(layoutId);
    }

    private void init(@LayoutRes int layoutId) {
        LayoutInflater.from(getContext()).inflate(layoutId, this, true);
        addAccountAction = findViewById(R.id.add_account_action);
        addAccountAction.setOnClickListener(this);
        findViewById(R.id.new_account_action).setOnClickListener(this);
        findViewById(R.id.import_account_action).setOnClickListener(this);
        findViewById(R.id.watch_account_action).setOnClickListener(this);
        findViewById(R.id.hardware_card).setOnClickListener(this);
        
        // Set up info button click listeners
        setupInfoButton(R.id.info_add_account, R.string.menu_add_account_detail_title, R.string.menu_add_account_detail_content);
        setupInfoButton(R.id.info_new_account, R.string.menu_create_wallet_detail_title, R.string.menu_create_wallet_detail_content);
        setupInfoButton(R.id.info_import_account, R.string.menu_import_wallet_detail_title, R.string.menu_import_wallet_detail_content);
        setupInfoButton(R.id.info_watch_account, R.string.menu_watch_wallet_detail_title, R.string.menu_watch_wallet_detail_content);
        setupInfoButton(R.id.info_hardware_card, R.string.menu_hardware_card_detail_title, R.string.menu_hardware_card_detail_content);
    }
    
    private void setupInfoButton(int buttonId, int titleResId, int contentResId) {
        View infoButton = findViewById(buttonId);
        if (infoButton != null) {
            infoButton.setOnClickListener(v -> showInfoDialog(titleResId, contentResId));
        }
    }
    
    private void showInfoDialog(int titleResId, int contentResId) {
        Context context = getContext();
        String title = context.getString(titleResId);
        String content = context.getString(contentResId);
        
        // Create scrollable text view for long content
        ScrollView scrollView = new ScrollView(context);
        TextView textView = new TextView(context);
        int padding = (int) (20 * context.getResources().getDisplayMetrics().density);
        textView.setPadding(padding, padding, padding, padding);
        textView.setText(Html.fromHtml(content, Html.FROM_HTML_MODE_COMPACT));
        textView.setTextSize(15);
        textView.setLineSpacing(0, 1.3f);
        scrollView.addView(textView);
        
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setView(scrollView)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    @Override
    public void onClick(View view)
    {
        if (view.getId() == R.id.close_action)
        {
            if (onCloseActionListener != null)
            {
                onCloseActionListener.onClose(view);
            }
        }
        else if (view.getId() == R.id.add_account_action)
        {
            if (onAddAccountClickListener != null)
            {
                onAddAccountClickListener.onAddAccount(view);
            }
        }
        else if (view.getId() == R.id.new_account_action)
        {
            if (onNewWalletClickListener != null)
            {
                onNewWalletClickListener.onNewWallet(view);
            }
        }
        else if (view.getId() == R.id.import_account_action)
        {
            if (onImportWalletClickListener != null)
            {
                onImportWalletClickListener.onImportWallet(view);
            }
        }
        else if (view.getId() == R.id.watch_account_action)
        {
            if (onWatchWalletClickListener != null)
            {
                onWatchWalletClickListener.onWatchWallet(view);
            }
        }
        else if (view.getId() == R.id.hardware_card)
        {
            if (onHardwareCardClickListener != null)
            {
                onHardwareCardClickListener.detectCard(view);
            }
        }
    }

    public void setOnNewWalletClickListener(OnNewWalletClickListener onNewWalletClickListener) {
        this.onNewWalletClickListener = onNewWalletClickListener;
    }

    public void setOnImportWalletClickListener(OnImportWalletClickListener onImportWalletClickListener) {
        this.onImportWalletClickListener = onImportWalletClickListener;
    }

    public void setOnWatchWalletClickListener(OnWatchWalletClickListener onWatchWalletClickListener) {
        this.onWatchWalletClickListener = onWatchWalletClickListener;
    }

    public void setOnHardwareCardClickListener(OnHardwareCardActionListener onHardwareCardClickListener)
    {
        this.onHardwareCardClickListener = onHardwareCardClickListener;
    }

    public void setOnAddAccountClickListener(OnAddAccountClickListener listener)
    {
        this.onAddAccountClickListener = listener;
    }

    public void setOnCloseActionListener(OnCloseActionListener onCloseActionListener) {
        this.onCloseActionListener = onCloseActionListener;
    }

    /**
     * Show the Add Account option if the user has an HD wallet
     */
    public void setHasHDWallet(boolean hasHDWallet)
    {
        if (addAccountAction != null)
        {
            addAccountAction.setVisibility(hasHDWallet ? View.VISIBLE : View.GONE);
        }
    }

    public interface OnNewWalletClickListener {
        void onNewWallet(View view);
    }

    public interface OnImportWalletClickListener {
        void onImportWallet(View view);
    }

    public interface OnWatchWalletClickListener {
        void onWatchWallet(View view);
    }

    public interface OnCloseActionListener {
        void onClose(View view);
    }

    public interface OnHardwareCardActionListener
    {
        void detectCard(View view);
    }

    public interface OnAddAccountClickListener
    {
        void onAddAccount(View view);
    }

    public void setHardwareActive(boolean isStub)
    {
        View hardwareView = findViewById(R.id.hardware_card);
        if (isStub && hardwareView != null)
        {
            hardwareView.setVisibility(View.GONE);
        }
    }
}
