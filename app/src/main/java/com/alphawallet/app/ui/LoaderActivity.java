package com.alphawallet.app.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.alphawallet.app.R;
import com.alphawallet.app.widget.PercentageProgressView;
import com.bumptech.glide.Glide;

public class LoaderActivity extends AppCompatActivity {
    
    private final Handler handler = new Handler(Looper.getMainLooper());
    private View dot1, dot2, dot3;
    private boolean isDestroyed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Make status bar transparent for immersive experience
        getWindow().setStatusBarColor(Color.parseColor("#0D0D1A"));
        getWindow().setNavigationBarColor(Color.parseColor("#0D0D1A"));
        
        setContentView(R.layout.activity_loader);

        // Load rotating GIF
        ImageView loaderGif = findViewById(R.id.initial_loader_gif);
        Glide.with(this)
            .asGif()
            .load(R.drawable.splash_loader)
            .into(loaderGif);

        // Initialize dots
        dot1 = findViewById(R.id.dot1);
        dot2 = findViewById(R.id.dot2);
        dot3 = findViewById(R.id.dot3);

        // Apply gradient to Ramestta text
        applyGradientToRamesttaText();

        // Start all animations immediately
        startLoaderAnimations();
        startDotAnimation();

        // Initialize and start percentage progress
        PercentageProgressView percentageProgress = findViewById(R.id.percentage_progress);
        percentageProgress.startSimulation(2500, () -> {
            if (!isDestroyed) {
                // Transition to SplashActivity when progress completes
                startActivity(new Intent(LoaderActivity.this, SplashActivity.class));
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                finish();
            }
        });
    }
    
    /**
     * Apply gold gradient to Ramestta Network text
     */
    private void applyGradientToRamesttaText() {
        TextView ramesttaText = findViewById(R.id.text_ramestta);
        if (ramesttaText != null) {
            ramesttaText.post(() -> {
                float width = ramesttaText.getPaint().measureText(ramesttaText.getText().toString());
                LinearGradient gradient = new LinearGradient(
                    0, 0, width, 0,
                    new int[]{
                        Color.parseColor("#D4AF37"),
                        Color.parseColor("#FFD700"),
                        Color.parseColor("#D4AF37")
                    },
                    new float[]{0.0f, 0.5f, 1.0f},
                    Shader.TileMode.CLAMP
                );
                ramesttaText.getPaint().setShader(gradient);
                ramesttaText.invalidate();
            });
        }
    }
    
    /**
     * Animate loading dots sequentially
     */
    private void startDotAnimation() {
        if (dot1 == null || dot2 == null || dot3 == null) return;
        
        // Set initial alpha
        dot1.setAlpha(0.3f);
        dot2.setAlpha(0.3f);
        dot3.setAlpha(0.3f);
        
        Runnable dotAnimator = new Runnable() {
            int currentDot = 0;
            
            @Override
            public void run() {
                if (isDestroyed) return;
                
                // Reset all dots
                dot1.animate().alpha(0.3f).scaleX(1f).scaleY(1f).setDuration(200).start();
                dot2.animate().alpha(0.3f).scaleX(1f).scaleY(1f).setDuration(200).start();
                dot3.animate().alpha(0.3f).scaleX(1f).scaleY(1f).setDuration(200).start();
                
                // Animate current dot
                View activeDot = currentDot == 0 ? dot1 : (currentDot == 1 ? dot2 : dot3);
                activeDot.animate()
                    .alpha(1f)
                    .scaleX(1.5f)
                    .scaleY(1.5f)
                    .setDuration(300)
                    .start();
                
                currentDot = (currentDot + 1) % 3;
                handler.postDelayed(this, 350);
            }
        };
        
        handler.postDelayed(dotAnimator, 200);
    }
    
    /**
     * Start all loader animations for futuristic effect
     */
    private void startLoaderAnimations()
    {
        // Rotating inner ring (gold gradient) - clockwise
        ImageView ringInner = findViewById(R.id.ring_inner);
        if (ringInner != null)
        {
            ObjectAnimator rotateInner = ObjectAnimator.ofFloat(ringInner, "rotation", 0f, 360f);
            rotateInner.setDuration(3000);
            rotateInner.setRepeatCount(ValueAnimator.INFINITE);
            rotateInner.setInterpolator(new LinearInterpolator());
            rotateInner.start();
        }
        
        // Rotating outer ring (purple) - counter-clockwise
        ImageView ringOuter = findViewById(R.id.ring_outer);
        if (ringOuter != null)
        {
            ObjectAnimator rotateOuter = ObjectAnimator.ofFloat(ringOuter, "rotation", 0f, -360f);
            rotateOuter.setDuration(4000);
            rotateOuter.setRepeatCount(ValueAnimator.INFINITE);
            rotateOuter.setInterpolator(new LinearInterpolator());
            rotateOuter.start();
        }
        
        // Particle ring - fast rotation
        ImageView ringParticle = findViewById(R.id.ring_particle);
        if (ringParticle != null)
        {
            ObjectAnimator rotateParticle = ObjectAnimator.ofFloat(ringParticle, "rotation", 0f, 360f);
            rotateParticle.setDuration(2000);
            rotateParticle.setRepeatCount(ValueAnimator.INFINITE);
            rotateParticle.setInterpolator(new LinearInterpolator());
            rotateParticle.start();
            
            // Also add alpha pulse
            ObjectAnimator alphaParticle = ObjectAnimator.ofFloat(ringParticle, "alpha", 0.5f, 1f);
            alphaParticle.setDuration(1000);
            alphaParticle.setRepeatCount(ValueAnimator.INFINITE);
            alphaParticle.setRepeatMode(ValueAnimator.REVERSE);
            alphaParticle.start();
        }
        
        // Energy ring - medium speed, opposite direction
        ImageView ringEnergy = findViewById(R.id.ring_energy);
        if (ringEnergy != null)
        {
            ObjectAnimator rotateEnergy = ObjectAnimator.ofFloat(ringEnergy, "rotation", 0f, -360f);
            rotateEnergy.setDuration(2500);
            rotateEnergy.setRepeatCount(ValueAnimator.INFINITE);
            rotateEnergy.setInterpolator(new LinearInterpolator());
            rotateEnergy.start();
        }
        
        // Pulsing outer glow effect
        ImageView glowOuter = findViewById(R.id.glow_outer);
        if (glowOuter != null)
        {
            ObjectAnimator scaleX = ObjectAnimator.ofFloat(glowOuter, "scaleX", 1f, 1.12f);
            ObjectAnimator scaleY = ObjectAnimator.ofFloat(glowOuter, "scaleY", 1f, 1.12f);
            ObjectAnimator alpha = ObjectAnimator.ofFloat(glowOuter, "alpha", 0.6f, 1f);
            
            scaleX.setDuration(1500);
            scaleX.setRepeatCount(ValueAnimator.INFINITE);
            scaleX.setRepeatMode(ValueAnimator.REVERSE);
            
            scaleY.setDuration(1500);
            scaleY.setRepeatCount(ValueAnimator.INFINITE);
            scaleY.setRepeatMode(ValueAnimator.REVERSE);
            
            alpha.setDuration(1500);
            alpha.setRepeatCount(ValueAnimator.INFINITE);
            alpha.setRepeatMode(ValueAnimator.REVERSE);
            
            scaleX.start();
            scaleY.start();
            alpha.start();
        }
        
        // Inner glow pulse (faster)
        ImageView glowInner = findViewById(R.id.glow_inner);
        if (glowInner != null)
        {
            ObjectAnimator alphaInner = ObjectAnimator.ofFloat(glowInner, "alpha", 0.5f, 1f);
            alphaInner.setDuration(800);
            alphaInner.setRepeatCount(ValueAnimator.INFINITE);
            alphaInner.setRepeatMode(ValueAnimator.REVERSE);
            alphaInner.start();
            
            ObjectAnimator scaleInnerX = ObjectAnimator.ofFloat(glowInner, "scaleX", 1f, 1.1f);
            ObjectAnimator scaleInnerY = ObjectAnimator.ofFloat(glowInner, "scaleY", 1f, 1.1f);
            scaleInnerX.setDuration(800);
            scaleInnerX.setRepeatCount(ValueAnimator.INFINITE);
            scaleInnerX.setRepeatMode(ValueAnimator.REVERSE);
            scaleInnerY.setDuration(800);
            scaleInnerY.setRepeatCount(ValueAnimator.INFINITE);
            scaleInnerY.setRepeatMode(ValueAnimator.REVERSE);
            scaleInnerX.start();
            scaleInnerY.start();
        }
        
        // Logo breathing animation
        ImageView loaderGif = findViewById(R.id.initial_loader_gif);
        if (loaderGif != null)
        {
            ObjectAnimator scaleX = ObjectAnimator.ofFloat(loaderGif, "scaleX", 1f, 1.05f);
            ObjectAnimator scaleY = ObjectAnimator.ofFloat(loaderGif, "scaleY", 1f, 1.05f);
            
            scaleX.setDuration(1500);
            scaleX.setRepeatCount(ValueAnimator.INFINITE);
            scaleX.setRepeatMode(ValueAnimator.REVERSE);
            scaleX.setInterpolator(new AccelerateDecelerateInterpolator());
            
            scaleY.setDuration(1500);
            scaleY.setRepeatCount(ValueAnimator.INFINITE);
            scaleY.setRepeatMode(ValueAnimator.REVERSE);
            scaleY.setInterpolator(new AccelerateDecelerateInterpolator());
            
            scaleX.start();
            scaleY.start();
        }
        
        // App name shimmer effect
        TextView appName = findViewById(R.id.text_app_name);
        if (appName != null) {
            ObjectAnimator shimmer = ObjectAnimator.ofFloat(appName, "alpha", 0.8f, 1f);
            shimmer.setDuration(1200);
            shimmer.setRepeatCount(ValueAnimator.INFINITE);
            shimmer.setRepeatMode(ValueAnimator.REVERSE);
            shimmer.start();
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        isDestroyed = true;
        handler.removeCallbacksAndMessages(null);
    }
}
