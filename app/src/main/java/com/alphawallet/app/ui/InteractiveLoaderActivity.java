package com.alphawallet.app.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

import com.alphawallet.app.R;

/**
 * Interactive loader screen with a cute squirrel that moves with device sensor
 * Features parallax depth effect with shadow for 3D-like appearance
 */
public class InteractiveLoaderActivity extends AppCompatActivity implements SensorEventListener {

    private SensorManager sensorManager;
    private Sensor accelerometer;
    
    private ImageView squirrel;
    private ImageView squirrelShadow;
    private ImageView ringOuter;
    private ImageView ringMiddle;
    private ImageView ringInner;
    
    // Screen dimensions
    private int screenWidth;
    private int screenHeight;
    
    // Squirrel position
    private float squirrelX = 0f;
    private float squirrelY = 0f;
    private static final float MOVEMENT_SCALE = 12f;
    private static final float SHADOW_OFFSET_SCALE = 0.3f; // Shadow moves less for depth effect
    private static final float SMOOTHING = 0.12f;
    
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isDestroyed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Make status bar transparent
        getWindow().setStatusBarColor(Color.parseColor("#0D0D1A"));
        getWindow().setNavigationBarColor(Color.parseColor("#0D0D1A"));
        
        setContentView(R.layout.activity_interactive_loader);
        
        // Get screen dimensions
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        screenWidth = displayMetrics.widthPixels;
        screenHeight = displayMetrics.heightPixels;
        
        initViews();
        initSensors();
        startRingAnimations();
        startIdleAnimation();
        
        // Transition to SplashActivity after 3 seconds
        handler.postDelayed(() -> {
            if (!isDestroyed) {
                startActivity(new Intent(InteractiveLoaderActivity.this, SplashActivity.class));
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                finish();
            }
        }, 3000);
    }
    
    private void initViews() {
        squirrel = findViewById(R.id.squirrel);
        squirrelShadow = findViewById(R.id.squirrel_shadow);
        ringOuter = findViewById(R.id.ring_outer);
        ringMiddle = findViewById(R.id.ring_middle);
        ringInner = findViewById(R.id.ring_inner);
    }
    
    private void initSensors() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
    }
    
    private void startRingAnimations() {
        // Outer ring - slow clockwise
        ObjectAnimator outerAnim = ObjectAnimator.ofFloat(ringOuter, "rotation", 0f, 360f);
        outerAnim.setDuration(8000);
        outerAnim.setRepeatCount(ValueAnimator.INFINITE);
        outerAnim.setInterpolator(new LinearInterpolator());
        outerAnim.start();
        
        // Middle ring - medium counter-clockwise
        ObjectAnimator middleAnim = ObjectAnimator.ofFloat(ringMiddle, "rotation", 0f, -360f);
        middleAnim.setDuration(6000);
        middleAnim.setRepeatCount(ValueAnimator.INFINITE);
        middleAnim.setInterpolator(new LinearInterpolator());
        middleAnim.start();
        
        // Inner ring - fast clockwise
        ObjectAnimator innerAnim = ObjectAnimator.ofFloat(ringInner, "rotation", 0f, 360f);
        innerAnim.setDuration(4000);
        innerAnim.setRepeatCount(ValueAnimator.INFINITE);
        innerAnim.setInterpolator(new LinearInterpolator());
        innerAnim.start();
    }
    
    private void startIdleAnimation() {
        // Gentle floating animation for the squirrel
        if (squirrel != null) {
            ObjectAnimator floatAnim = ObjectAnimator.ofFloat(squirrel, "translationY", 0f, -15f, 0f);
            floatAnim.setDuration(2000);
            floatAnim.setRepeatCount(ValueAnimator.INFINITE);
            floatAnim.setInterpolator(new LinearInterpolator());
            floatAnim.start();
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        if (sensorManager != null && accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        }
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        isDestroyed = true;
        handler.removeCallbacksAndMessages(null);
    }
    
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER && squirrel != null) {
            // Get accelerometer values
            float x = event.values[0]; // Left/Right tilt
            float y = event.values[1]; // Forward/Backward tilt
            
            // Calculate target position based on tilt
            float targetX = -x * MOVEMENT_SCALE;
            float targetY = y * MOVEMENT_SCALE;
            
            // Apply smoothing for fluid motion
            squirrelX = squirrelX + (targetX - squirrelX) * SMOOTHING;
            squirrelY = squirrelY + (targetY - squirrelY) * SMOOTHING;
            
            // Limit movement range
            float maxMove = 80f;
            squirrelX = Math.max(-maxMove, Math.min(maxMove, squirrelX));
            squirrelY = Math.max(-maxMove, Math.min(maxMove, squirrelY));
            
            // Apply translation to squirrel
            squirrel.setTranslationX(squirrelX);
            // Note: Y translation is handled by idle animation, we add to it
            
            // Slight rotation based on horizontal movement (3D tilt effect)
            squirrel.setRotation(squirrelX * 0.3f);
            
            // Scale slightly based on tilt for depth illusion
            float scale = 1.0f + (squirrelY * 0.002f);
            scale = Math.max(0.9f, Math.min(1.1f, scale));
            squirrel.setScaleX(scale);
            squirrel.setScaleY(scale);
            
            // Move shadow with offset for parallax/depth effect
            if (squirrelShadow != null) {
                // Shadow moves less and in opposite direction for depth
                squirrelShadow.setTranslationX(squirrelX * SHADOW_OFFSET_SCALE + 5);
                squirrelShadow.setTranslationY(squirrelY * SHADOW_OFFSET_SCALE + 5);
                
                // Shadow gets more transparent when squirrel is "higher"
                float shadowAlpha = 0.3f - (squirrelY * 0.002f);
                shadowAlpha = Math.max(0.1f, Math.min(0.4f, shadowAlpha));
                squirrelShadow.setAlpha(shadowAlpha);
                
                // Shadow spreads/shrinks based on height
                float shadowScale = 0.9f + (squirrelY * 0.002f);
                shadowScale = Math.max(0.8f, Math.min(1.0f, shadowScale));
                squirrelShadow.setScaleX(shadowScale);
                squirrelShadow.setScaleY(shadowScale);
            }
        }
    }
    
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not needed
    }
}
