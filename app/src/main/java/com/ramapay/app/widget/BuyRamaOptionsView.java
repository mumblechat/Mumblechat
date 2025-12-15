package com.ramapay.app.widget;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.LayoutRes;

import com.ramapay.app.C;
import com.ramapay.app.R;
import com.ramapay.app.entity.DialogDismissInterface;


public class BuyRamaOptionsView extends FrameLayout implements View.OnClickListener
{
    private OnClickListener onBuyFromBitMartListener;
    private OnClickListener onBuyFromKoinparkListener;
    private OnClickListener onBuyFromRamaSwapListener;
    private DialogDismissInterface dismissInterface;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public BuyRamaOptionsView(Context context)
    {
        this(context, R.layout.dialog_buy_rama_options);
    }

    public BuyRamaOptionsView(Context context, @LayoutRes int layoutId)
    {
        super(context);
        init(layoutId);
    }

    private void init(@LayoutRes int layoutId)
    {
        LayoutInflater.from(getContext()).inflate(layoutId, this, true);
        
        // Set click listeners on LinearLayout containers
        findViewById(R.id.buy_from_bitmart).setOnClickListener(this);
        findViewById(R.id.buy_from_koinpark).setOnClickListener(this);
        findViewById(R.id.buy_from_ramaswap).setOnClickListener(this);
        
        // Set info button click listeners
        View infoBitmart = findViewById(R.id.info_buy_bitmart);
        View infoKoinpark = findViewById(R.id.info_buy_koinpark);
        View infoRamaswap = findViewById(R.id.info_buy_ramaswap);
        
        if (infoBitmart != null) {
            infoBitmart.setOnClickListener(v -> showInfoDialog(
                getContext().getString(R.string.buy_from_bitmart_detail_title),
                getContext().getString(R.string.buy_from_bitmart_detail_content)
            ));
        }
        
        if (infoKoinpark != null) {
            infoKoinpark.setOnClickListener(v -> showInfoDialog(
                getContext().getString(R.string.buy_from_koinpark_detail_title),
                getContext().getString(R.string.buy_from_koinpark_detail_content)
            ));
        }
        
        if (infoRamaswap != null) {
            infoRamaswap.setOnClickListener(v -> showInfoDialog(
                getContext().getString(R.string.buy_from_ramaswap_detail_title),
                getContext().getString(R.string.buy_from_ramaswap_detail_content)
            ));
        }

        //close after 30 seconds of inactivity
        handler.postDelayed(closePopup, C.STANDARD_POPUP_INACTIVITY_DISMISS);
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

    private final Runnable closePopup = () -> {
        if (dismissInterface != null) {
            dismissInterface.dismissDialog();
        }
    };

    @Override
    public void onClick(View view)
    {
        handler.removeCallbacks(closePopup);
        
        int viewId = view.getId();
        if (viewId == R.id.buy_from_bitmart)
        {
            if (onBuyFromBitMartListener != null)
            {
                onBuyFromBitMartListener.onClick(view);
            }
        }
        else if (viewId == R.id.buy_from_koinpark)
        {
            if (onBuyFromKoinparkListener != null)
            {
                onBuyFromKoinparkListener.onClick(view);
            }
        }
        else if (viewId == R.id.buy_from_ramaswap)
        {
            if (onBuyFromRamaSwapListener != null)
            {
                onBuyFromRamaSwapListener.onClick(view);
            }
        }
    }

    public void setOnBuyFromBitMartListener(OnClickListener onClickListener)
    {
        this.onBuyFromBitMartListener = onClickListener;
    }

    public void setOnBuyFromKoinparkListener(OnClickListener onClickListener)
    {
        this.onBuyFromKoinparkListener = onClickListener;
    }

    public void setOnBuyFromRamaSwapListener(OnClickListener onClickListener)
    {
        this.onBuyFromRamaSwapListener = onClickListener;
    }

    public void setDismissInterface(DialogDismissInterface dismissInterface)
    {
        this.dismissInterface = dismissInterface;
    }
}
