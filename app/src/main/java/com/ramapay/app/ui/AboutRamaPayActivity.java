package com.ramapay.app.ui;

import android.os.Bundle;
import android.view.MenuItem;
import android.widget.TextView;

import com.ramapay.app.BuildConfig;
import com.ramapay.app.R;

import java.util.Locale;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class AboutRamaPayActivity extends BaseActivity
{
    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about_ramapay);
        
        toolbar();
        setTitle(getString(R.string.title_about_ramapay));
        enableDisplayHomeAsUp();
        
        // Set version info
        TextView versionInfo = findViewById(R.id.text_version_info);
        versionInfo.setText(String.format(Locale.getDefault(), 
            "Version %s (Build %d)", 
            BuildConfig.VERSION_NAME, 
            BuildConfig.VERSION_CODE));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        if (item.getItemId() == android.R.id.home)
        {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
