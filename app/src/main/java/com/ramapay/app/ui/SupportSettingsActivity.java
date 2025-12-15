package com.ramapay.app.ui;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import com.ramapay.app.C;
import com.ramapay.app.R;
import com.ramapay.app.analytics.Analytics;
import com.ramapay.app.entity.MediaLinks;
import com.ramapay.app.viewmodel.SupportSettingsViewModel;
import com.ramapay.app.widget.SettingsItemView;

import dagger.hilt.android.AndroidEntryPoint;
import timber.log.Timber;

@AndroidEntryPoint
public class SupportSettingsActivity extends BaseActivity
{
    private SupportSettingsViewModel viewModel;
    private LinearLayout supportSettingsLayout;
    private SettingsItemView email;
    private SettingsItemView twitter;
    private SettingsItemView facebook;
    private SettingsItemView instagram;
    private SettingsItemView blog;
    private SettingsItemView faq;
    private SettingsItemView github;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_generic_settings);

        toolbar();

        setTitle(getString(R.string.title_support));

        initViewModel();

        initializeSettings();

        addSettingsToLayout();
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        viewModel.track(Analytics.Navigation.SETTINGS_SUPPORT);
    }

    private void initViewModel()
    {
        viewModel = new ViewModelProvider(this).get(SupportSettingsViewModel.class);
    }

    private void initializeSettings()
    {
        email = new SettingsItemView.Builder(this)
                .withIcon(R.drawable.ic_email)
                .withTitle(R.string.email)
                .withListener(this::onEmailClicked)
                .build();

        twitter = new SettingsItemView.Builder(this)
                .withIcon(R.drawable.ic_logo_twitter)
                .withTitle(R.string.twitter)
                .withListener(this::onTwitterClicked)
                .build();

        facebook = new SettingsItemView.Builder(this)
                .withIcon(R.drawable.ic_logo_facebook)
                .withTitle(R.string.facebook)
                .withListener(this::onFacebookClicked)
                .build();

        instagram = new SettingsItemView.Builder(this)
                .withIcon(R.drawable.ic_logo_instagram)
                .withTitle(R.string.instagram)
                .withListener(this::onInstagramClicked)
                .build();

        blog = new SettingsItemView.Builder(this)
                .withIcon(R.drawable.ic_settings_blog)
                .withTitle(R.string.title_blog)
                .withListener(this::onBlogClicked)
                .build();

        github = new SettingsItemView.Builder(this)
                .withIcon(R.drawable.ic_logo_github)
                .withTitle(R.string.github)
                .withListener(this::onGitHubClicked)
                .build();

        faq = new SettingsItemView.Builder(this)
                .withIcon(R.drawable.ic_settings_faq)
                .withTitle(R.string.title_faq)
                .withListener(this::onFaqClicked)
                .build();
    }

    private void addSettingsToLayout()
    {
        supportSettingsLayout = findViewById(R.id.layout);

        // Add all active support channels
        supportSettingsLayout.addView(twitter);
        supportSettingsLayout.addView(facebook);
        supportSettingsLayout.addView(instagram);
        supportSettingsLayout.addView(github);
        supportSettingsLayout.addView(blog);
        supportSettingsLayout.addView(email);
        supportSettingsLayout.addView(faq);
    }

    private void onGitHubClicked()
    {
        Intent intent = new Intent();
        intent.putExtra(C.DAPP_URL_LOAD, MediaLinks.AWALLET_GITHUB);
        viewModel.track(Analytics.Action.SUPPORT_GITHUB);
        setResult(RESULT_OK, intent);
        finish();
    }

    private void onEmailClicked()
    {
        Intent intent = new Intent(Intent.ACTION_SENDTO);
        final String at = "@";
        String email =
                "mailto:" + MediaLinks.AWALLET_EMAIL1 + at + MediaLinks.AWALLET_EMAIL2 +
                        "?subject=" + Uri.encode(MediaLinks.AWALLET_SUBJECT) +
                        "&body=" + Uri.encode("");
        intent.setData(Uri.parse(email));

        try
        {
            viewModel.track(Analytics.Action.SUPPORT_EMAIL);
            startActivity(intent);
        }
        catch (Exception e)
        {
            Timber.e(e);
        }
    }

    private void onTwitterClicked()
    {
        Intent intent = new Intent();
        intent.putExtra(C.DAPP_URL_LOAD, MediaLinks.AWALLET_TWITTER_URL);
        viewModel.track(Analytics.Action.SUPPORT_TWITTER);
        setResult(RESULT_OK, intent);
        finish();
    }

    private void onFacebookClicked()
    {
        Intent intent = new Intent();
        intent.putExtra(C.DAPP_URL_LOAD, MediaLinks.AWALLET_FACEBOOK_URL);
        setResult(RESULT_OK, intent);
        finish();
    }

    private void onInstagramClicked()
    {
        Intent intent = new Intent();
        intent.putExtra(C.DAPP_URL_LOAD, MediaLinks.AWALLET_INSTAGRAM_URL);
        setResult(RESULT_OK, intent);
        finish();
    }

    private void onBlogClicked()
    {
        Intent intent = new Intent();
        intent.putExtra(C.DAPP_URL_LOAD, MediaLinks.AWALLET_BLOG_URL);
        setResult(RESULT_OK, intent);
        finish();
    }

    private void onFaqClicked()
    {
        showFaqDialog();
    }

    private void showFaqDialog()
    {
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        builder.setTitle("Frequently Asked Questions");
        
        String[] faqItems = new String[]{
            "What is RamaPay?",
            "How to create a wallet?",
            "How to receive RAMA?",
            "How to send RAMA?",
            "How to buy RAMA?",
            "Is my wallet secure?",
            "How to backup my wallet?",
            "How to restore my wallet?",
            "What networks are supported?",
            "Visit our website for more help"
        };
        
        builder.setItems(faqItems, (dialog, which) -> {
            String answer = "";
            switch (which) {
                case 0:
                    answer = "RamaPay is a secure, non-custodial wallet for managing your RAMA tokens and interacting with the Ramestta blockchain ecosystem.";
                    break;
                case 1:
                    answer = "Tap 'Create New Wallet', securely write down your 12-word seed phrase, and verify it. Never share your seed phrase with anyone!";
                    break;
                case 2:
                    answer = "Share your wallet address with the sender. You can find it by tapping your account name at the top of the screen.";
                    break;
                case 3:
                    answer = "Tap 'Send', enter the recipient's address, amount, and confirm the transaction. Make sure you have enough RAMA for gas fees.";
                    break;
                case 4:
                    answer = "Tap 'Buy RAMA' and choose from our supported exchanges: Uniswap, MEXC, or Gate.io.";
                    break;
                case 5:
                    answer = "Yes! Your keys are encrypted and stored only on your device. Always backup your seed phrase and enable biometric security.";
                    break;
                case 6:
                    answer = "Go to Settings → Advanced → Show Seed Phrase. Write down your 12 words and store them safely offline. This is the ONLY way to restore your wallet!";
                    break;
                case 7:
                    answer = "Tap 'Import Wallet' when opening the app, enter your 12-word seed phrase to restore full access to your wallet.";
                    break;
                case 8:
                    answer = "RamaPay supports Ethereum, Polygon, BSC, Arbitrum, Optimism, and of course the Ramestta Network!";
                    break;
                case 9:
                    Intent intent = new Intent();
                    intent.putExtra(C.DAPP_URL_LOAD, "https://ramestta.com/faq");
                    setResult(RESULT_OK, intent);
                    finish();
                    return;
            }
            
            if (!answer.isEmpty()) {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(faqItems[which])
                    .setMessage(answer)
                    .setPositiveButton("Got it", null)
                    .setNeutralButton("Back to FAQ", (d, w) -> showFaqDialog())
                    .show();
            }
        });
        
        builder.setNegativeButton("Close", null);
        builder.show();
    }

    private boolean isAppAvailable(String packageName)
    {
        PackageManager pm = getPackageManager();
        try
        {
            pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES);
            return true;
        }
        catch (Exception e)
        {
            return false;
        }
    }
}
