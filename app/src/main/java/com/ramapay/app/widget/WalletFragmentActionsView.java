package com.ramapay.app.widget;

import android.app.AlertDialog;
import android.content.Context;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.LayoutRes;

import com.ramapay.app.R;


public class WalletFragmentActionsView extends FrameLayout implements View.OnClickListener {
    private OnClickListener onCopyWalletAddressClickListener;
    private OnClickListener onShowMyWalletAddressClickListener;
    private OnClickListener onAddHideTokensClickListener;
    private OnClickListener onRenameThisWalletListener;

    public WalletFragmentActionsView(Context context) {
        this(context, R.layout.layout_dialog_wallet_actions);
    }

    public WalletFragmentActionsView(Context context, @LayoutRes int layoutId) {
        super(context);

        init(layoutId);
    }

    private void init(@LayoutRes int layoutId) {
        LayoutInflater.from(getContext()).inflate(layoutId, this, true);
        
        // Set click listeners on the LinearLayout containers
        findViewById(R.id.copy_wallet_address_action).setOnClickListener(this);
        findViewById(R.id.show_my_wallet_address_action).setOnClickListener(this);
        findViewById(R.id.add_hide_tokens_action).setOnClickListener(this);
        findViewById(R.id.rename_this_wallet_action).setOnClickListener(this);
        
        // Set info button click listeners
        View infoCopyAddress = findViewById(R.id.info_copy_wallet_address);
        View infoShowAddress = findViewById(R.id.info_show_my_wallet_address);
        View infoAddHideTokens = findViewById(R.id.info_add_hide_tokens);
        View infoRenameWallet = findViewById(R.id.info_rename_this_wallet);
        
        if (infoCopyAddress != null) {
            infoCopyAddress.setOnClickListener(v -> showInfoDialog(
                getContext().getString(R.string.copy_wallet_address_detail_title),
                getContext().getString(R.string.copy_wallet_address_detail_content)
            ));
        }
        
        if (infoShowAddress != null) {
            infoShowAddress.setOnClickListener(v -> showInfoDialog(
                getContext().getString(R.string.show_my_wallet_address_detail_title),
                getContext().getString(R.string.show_my_wallet_address_detail_content)
            ));
        }
        
        if (infoAddHideTokens != null) {
            infoAddHideTokens.setOnClickListener(v -> showInfoDialog(
                getContext().getString(R.string.add_hide_tokens_detail_title),
                getContext().getString(R.string.add_hide_tokens_detail_content)
            ));
        }
        
        if (infoRenameWallet != null) {
            infoRenameWallet.setOnClickListener(v -> showInfoDialog(
                getContext().getString(R.string.rename_this_wallet_detail_title),
                getContext().getString(R.string.rename_this_wallet_detail_content)
            ));
        }
    }
    
    private void showInfoDialog(String title, String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(title);
        
        // Create a custom view for better text display
        TextView messageView = new TextView(getContext());
        messageView.setText(Html.fromHtml(message, Html.FROM_HTML_MODE_COMPACT));
        messageView.setPadding(48, 24, 48, 24);
        messageView.setTextSize(14);
        messageView.setLineSpacing(4, 1.2f);
        
        builder.setView(messageView);
        builder.setPositiveButton(R.string.action_close, null);
        builder.show();
    }

    @Override
    public void onClick(View view)
    {
        int viewId = view.getId();
        if (viewId == R.id.copy_wallet_address_action)
        {
            if (onCopyWalletAddressClickListener != null) {
                onCopyWalletAddressClickListener.onClick(view);
            }
        }
        else if (viewId == R.id.show_my_wallet_address_action)
        {
            if (onShowMyWalletAddressClickListener != null) {
                onShowMyWalletAddressClickListener.onClick(view);
            }
        }
        else if (viewId == R.id.add_hide_tokens_action)
        {
            if (onAddHideTokensClickListener != null) {
                onAddHideTokensClickListener.onClick(view);
            }
        }
        else if (viewId == R.id.rename_this_wallet_action)
        {
            if (onRenameThisWalletListener != null) {
                onRenameThisWalletListener.onClick(view);
            }
        }
    }

    public void setOnCopyWalletAddressClickListener(OnClickListener onClickListener) {
        this.onCopyWalletAddressClickListener = onClickListener;
    }

    public void setOnShowMyWalletAddressClickListener(OnClickListener onClickListener) {
        this.onShowMyWalletAddressClickListener = onClickListener;
    }

    public void setOnAddHideTokensClickListener(OnClickListener onClickListener) {
        this.onAddHideTokensClickListener = onClickListener;
    }

    public void setOnRenameThisWalletClickListener(OnClickListener onClickListener) {
        this.onRenameThisWalletListener = onClickListener;
    }
}
