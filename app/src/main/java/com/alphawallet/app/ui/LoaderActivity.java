package com.alphawallet.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

import com.alphawallet.app.R;
import com.alphawallet.app.widget.PercentageProgressView;
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

        // Initialize and start percentage progress
        PercentageProgressView percentageProgress = findViewById(R.id.percentage_progress);
        percentageProgress.startSimulation(2000, () -> {
            // Transition to SplashActivity when progress completes
            startActivity(new Intent(LoaderActivity.this, SplashActivity.class));
            finish();
        });
    }
}
