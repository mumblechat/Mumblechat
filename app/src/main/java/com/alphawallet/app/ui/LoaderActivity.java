package com.alphawallet.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

import com.alphawallet.app.R;
import com.bumptech.glide.Glide;

public class LoaderActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_loader);

        // Load rotating GIF
        ImageView loaderGif = findViewById(R.id.initial_loader_gif);
        Glide.with(this)
            .asGif()
            .load(R.drawable.splash_loader)
            .into(loaderGif);

        // Transition to SplashActivity after 2 seconds
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            startActivity(new Intent(LoaderActivity.this, SplashActivity.class));
            finish();
        }, 2000);
    }
}
