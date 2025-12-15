package com.ramapay.app.entity;

import android.graphics.Bitmap;

/**
 * Represents a browser tab with URL, title, and state
 */
public class BrowserTab {
    private final String id;
    private String url;
    private String title;
    private Bitmap favicon;
    private long lastAccessTime;
    private boolean isActive;

    public BrowserTab(String id, String url) {
        this.id = id;
        this.url = url;
        this.title = url;
        this.lastAccessTime = System.currentTimeMillis();
        this.isActive = false;
    }

    public String getId() {
        return id;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
        this.lastAccessTime = System.currentTimeMillis();
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Bitmap getFavicon() {
        return favicon;
    }

    public void setFavicon(Bitmap favicon) {
        this.favicon = favicon;
    }

    public long getLastAccessTime() {
        return lastAccessTime;
    }

    public void updateAccessTime() {
        this.lastAccessTime = System.currentTimeMillis();
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    public String getDisplayTitle() {
        if (title != null && !title.isEmpty() && !title.equals(url)) {
            return title;
        }
        return url != null ? url : "New Tab";
    }
}
