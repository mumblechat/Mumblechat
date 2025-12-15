package com.ramapay.app.widget;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ramapay.app.R;

/**
 * A widget that displays a percentage-based progress bar with text
 * showing 0% to 100% loading status
 */
public class PercentageProgressView extends RelativeLayout {
    
    private ProgressBar progressBar;
    private TextView percentageText;
    private TextView loadingMessage;
    private int currentProgress = 0;
    private boolean isSimulating = false;
    private Handler handler;
    private Runnable simulationRunnable;
    private OnProgressCompleteListener completeListener;

    public interface OnProgressCompleteListener {
        void onProgressComplete();
    }

    public PercentageProgressView(@NonNull Context context) {
        super(context);
        init(context);
    }

    public PercentageProgressView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public PercentageProgressView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        View view = LayoutInflater.from(context).inflate(R.layout.layout_percentage_progress, this, true);
        progressBar = view.findViewById(R.id.progress_bar);
        percentageText = view.findViewById(R.id.text_percentage);
        loadingMessage = view.findViewById(R.id.text_loading_message);
        handler = new Handler(Looper.getMainLooper());
        
        updateDisplay(0);
    }

    /**
     * Set the progress value directly (0-100)
     */
    public void setProgress(int progress) {
        if (progress < 0) progress = 0;
        if (progress > 100) progress = 100;
        
        currentProgress = progress;
        animateProgressTo(progress);
    }

    /**
     * Animate progress to a target value
     */
    private void animateProgressTo(int targetProgress) {
        ObjectAnimator animation = ObjectAnimator.ofInt(progressBar, "progress", progressBar.getProgress(), targetProgress);
        animation.setDuration(300);
        animation.setInterpolator(new DecelerateInterpolator());
        animation.start();
        
        updateDisplay(targetProgress);
    }

    /**
     * Update the percentage text display
     */
    private void updateDisplay(int progress) {
        percentageText.setText(progress + "%");
    }

    /**
     * Set a custom loading message
     */
    public void setLoadingMessage(String message) {
        if (message != null && !message.isEmpty()) {
            loadingMessage.setText(message);
            loadingMessage.setVisibility(View.VISIBLE);
        } else {
            loadingMessage.setVisibility(View.GONE);
        }
    }

    /**
     * Start simulating progress from 0% to 100% over a specified duration
     * @param durationMillis Total duration for the simulation
     */
    public void startSimulation(long durationMillis) {
        startSimulation(durationMillis, null);
    }

    /**
     * Start simulating progress from 0% to 100% over a specified duration
     * @param durationMillis Total duration for the simulation
     * @param listener Callback when progress reaches 100%
     */
    public void startSimulation(long durationMillis, OnProgressCompleteListener listener) {
        stopSimulation();
        
        this.completeListener = listener;
        isSimulating = true;
        currentProgress = 0;
        setVisibility(View.VISIBLE);
        
        final long updateInterval = 50; // Update every 50ms
        final int totalUpdates = (int) (durationMillis / updateInterval);
        final float progressPerUpdate = 100f / totalUpdates;
        
        simulationRunnable = new Runnable() {
            private float accumulatedProgress = 0;
            
            @Override
            public void run() {
                if (!isSimulating) return;
                
                accumulatedProgress += progressPerUpdate;
                int displayProgress = Math.min(100, (int) accumulatedProgress);
                
                progressBar.setProgress(displayProgress);
                updateDisplay(displayProgress);
                
                if (displayProgress < 100) {
                    handler.postDelayed(this, updateInterval);
                } else {
                    isSimulating = false;
                    if (completeListener != null) {
                        completeListener.onProgressComplete();
                    }
                }
            }
        };
        
        handler.post(simulationRunnable);
    }

    /**
     * Stop the simulation
     */
    public void stopSimulation() {
        isSimulating = false;
        if (handler != null && simulationRunnable != null) {
            handler.removeCallbacks(simulationRunnable);
        }
    }

    /**
     * Reset progress to 0%
     */
    public void reset() {
        stopSimulation();
        currentProgress = 0;
        progressBar.setProgress(0);
        updateDisplay(0);
    }

    /**
     * Show the progress view
     */
    public void show() {
        setVisibility(View.VISIBLE);
    }

    /**
     * Hide the progress view
     */
    public void hide() {
        stopSimulation();
        setVisibility(View.GONE);
    }

    /**
     * Get current progress value
     */
    public int getCurrentProgress() {
        return currentProgress;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopSimulation();
    }
}
