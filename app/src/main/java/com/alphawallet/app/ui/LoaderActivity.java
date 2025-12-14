package com.alphawallet.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.alphawallet.app.R;
import com.alphawallet.app.widget.PercentageProgressView;
import com.bumptech.glide.Glide;

public class LoaderActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Set window background for futuristic theme
        getWindow().setBackgroundDrawableResource(R.drawable.futuristic_background);
        
        setContentView(R.layout.activity_loader);

        // Load rotating GIF
        ImageView loaderGif = findViewById(R.id.initial_loader_gif);
        Glide.with(this)
            .asGif()
            .load(R.drawable.splash_loader)
            .into(loaderGif);

        // Start animations
        startLoaderAnimations();

        // Initialize and start percentage progress
        PercentageProgressView percentageProgress = findViewById(R.id.percentage_progress);
        percentageProgress.startSimulation(2000, () -> {
            // Transition to SplashActivity when progress completes
            startActivity(new Intent(LoaderActivity.this, SplashActivity.class));
            finish();
        });
    }
    
    /**
     * Start all loader animations for futuristic effect
     */
    private void startLoaderAnimations()
    {
        // Rotating inner ring (gold gradient)
        ImageView ringInner = findViewById(R.id.ring_inner);
        if (ringInner != null)
        {
            Animation rotateRing = AnimationUtils.loadAnimation(this, R.anim.rotate_ring);
            ringInner.startAnimation(rotateRing);
        }
        
        // Rotating outer ring (purple) - reverse direction
        ImageView ringOuter = findViewById(R.id.ring_outer);
        if (ringOuter != null)
        {
            Animation rotateRingReverse = AnimationUtils.loadAnimation(this, R.anim.rotate_ring_reverse);
            ringOuter.startAnimation(rotateRingReverse);
        }
        
        // Pulsing glow effect
        ImageView glowOuter = findViewById(R.id.glow_outer);
        if (glowOuter != null)
        {
            Animation pulseGlow = AnimationUtils.loadAnimation(this, R.anim.pulse_glow);
            glowOuter.startAnimation(pulseGlow);
        }
        
        // Floating logo animation
        ImageView loaderGif = findViewById(R.id.initial_loader_gif);
        if (loaderGif != null)
        {
            Animation logoFloat = AnimationUtils.loadAnimation(this, R.anim.logo_float);
            loaderGif.startAnimation(logoFloat);
        }
        
        // Fade in text animation
        TextView loadingText = findViewById(R.id.text_loading);
        if (loadingText != null)
        {
            Animation fadeInUp = AnimationUtils.loadAnimation(this, R.anim.fade_in_up);
            fadeInUp.setStartOffset(300);
            loadingText.startAnimation(fadeInUp);
        }
    }
}
