package com.ramapay.app.service;

import com.ramapay.app.entity.BrowserTab;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Manages browser tabs - creation, switching, closing
 */
public class TabManager {
    private static TabManager instance;
    private final List<BrowserTab> tabs;
    private BrowserTab activeTab;

    private TabManager() {
        tabs = new ArrayList<>();
    }

    public static synchronized TabManager getInstance() {
        if (instance == null) {
            instance = new TabManager();
        }
        return instance;
    }

    public BrowserTab createNewTab(String url) {
        String tabId = UUID.randomUUID().toString();
        BrowserTab newTab = new BrowserTab(tabId, url);
        
        // Deactivate current active tab
        if (activeTab != null) {
            activeTab.setActive(false);
        }
        
        newTab.setActive(true);
        tabs.add(newTab);
        activeTab = newTab;
        
        return newTab;
    }

    public void switchToTab(String tabId) {
        for (BrowserTab tab : tabs) {
            if (tab.getId().equals(tabId)) {
                if (activeTab != null) {
                    activeTab.setActive(false);
                }
                tab.setActive(true);
                tab.updateAccessTime();
                activeTab = tab;
                break;
            }
        }
    }

    public void closeTab(String tabId) {
        BrowserTab tabToRemove = null;
        for (BrowserTab tab : tabs) {
            if (tab.getId().equals(tabId)) {
                tabToRemove = tab;
                break;
            }
        }
        
        if (tabToRemove != null) {
            tabs.remove(tabToRemove);
            
            // If closing active tab, switch to most recent tab
            if (tabToRemove.isActive() && !tabs.isEmpty()) {
                BrowserTab mostRecent = tabs.get(tabs.size() - 1);
                switchToTab(mostRecent.getId());
            } else if (tabs.isEmpty()) {
                activeTab = null;
            }
        }
    }

    public void updateActiveTabUrl(String url) {
        if (activeTab != null) {
            activeTab.setUrl(url);
        }
    }

    public void updateActiveTabTitle(String title) {
        if (activeTab != null) {
            activeTab.setTitle(title);
        }
    }

    public BrowserTab getActiveTab() {
        return activeTab;
    }

    public List<BrowserTab> getAllTabs() {
        return new ArrayList<>(tabs);
    }

    public int getTabCount() {
        return tabs.size();
    }

    public void closeAllTabs() {
        tabs.clear();
        activeTab = null;
    }
}
