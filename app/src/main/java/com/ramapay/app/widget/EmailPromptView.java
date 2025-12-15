package com.ramapay.app.widget;

import android.content.Context;
import android.os.Handler;
import android.text.InputType;
import android.util.Patterns;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.LayoutRes;

import com.ramapay.app.R;
import com.ramapay.app.entity.StandardFunctionInterface;
import com.ramapay.app.util.KeyboardUtils;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import timber.log.Timber;



public class EmailPromptView extends LinearLayout implements StandardFunctionInterface {

    private BottomSheetDialog parentDialog;
    
    // Formspree endpoint for email subscriptions
    // Emails will be forwarded to ramesttablockchain@gmail.com
    private static final String FORMSPREE_ENDPOINT = "https://formspree.io/f/mldqoqve";
    private static final MediaType FORM_URLENCODED = MediaType.get("application/x-www-form-urlencoded");

    public void setParentDialog(BottomSheetDialog parentDialog) {
        this.parentDialog = parentDialog;
    }

    private InputView emailInput;
    private final View successOverlay;
    private final Handler handler;
    private final Runnable onSuccessRunnable;

    public EmailPromptView(Context context, View successOverlay, Handler handler, Runnable onSuccessRunnable) {
        super(context);
        this.successOverlay = successOverlay;
        this.handler = handler;
        this.onSuccessRunnable = onSuccessRunnable;
        init(R.layout.layout_dialog_email_prompt);
    }

    private void init(@LayoutRes int layoutId) {
        LayoutInflater.from(getContext()).inflate(layoutId, this, true);

        FunctionButtonBar functionBar = findViewById(R.id.layoutButtons);
        functionBar.setupFunctions(this, new ArrayList<>(Collections.singletonList(R.string.action_want_to_receive_email)));
        functionBar.revealButtons();

        emailInput = findViewById(R.id.email_input);
        emailInput.getEditText().setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        emailInput.getEditText().setOnKeyListener(new OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                    handleClick(getContext().getString(R.string.action_want_to_receive_email), 0);
                    return true;
                }
                return false;
            }
        });
    }

    @Override
    public void handleClick(String action, int actionId) {

        if (action.equals(getContext().getString(R.string.action_want_to_receive_email))) {
            // validate email
            String email = emailInput.getText().toString();
            if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                emailInput.setError(R.string.email_is_invalid);
                return ;
            }

            try {
                KeyboardUtils.hideKeyboard(this);
                
                // Send email subscription to webhook endpoint
                sendEmailSubscription(email);
                
            } catch (Exception e) {
                Timber.e(e, "Error sending email subscription");
            }

            parentDialog.dismiss();

            if (successOverlay != null) successOverlay.setVisibility(View.VISIBLE);
            handler.postDelayed(onSuccessRunnable, 1000);
        }
    }
    
    private void sendEmailSubscription(String email) {
        OkHttpClient client = new OkHttpClient();
        
        // Create form data for Formspree
        String formData = "email=" + email + "&message=New email subscription from RamaPay Android App";
        
        RequestBody body = RequestBody.create(formData, FORM_URLENCODED);
        Request request = new Request.Builder()
                .url(FORMSPREE_ENDPOINT)
                .post(body)
                .addHeader("Accept", "application/json")
                .build();
        
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Timber.e(e, "Failed to send email subscription");
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    Timber.d("Email subscription sent successfully for: %s", email);
                } else {
                    Timber.w("Email subscription failed with code: %d", response.code());
                }
                response.close();
            }
        });
    }
}
