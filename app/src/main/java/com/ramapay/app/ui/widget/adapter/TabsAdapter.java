package com.ramapay.app.ui.widget.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

import com.ramapay.app.R;
import com.ramapay.app.entity.BrowserTab;

import java.util.ArrayList;
import java.util.List;

public class TabsAdapter extends RecyclerView.Adapter<TabsAdapter.TabViewHolder> {
    
    private List<BrowserTab> tabs = new ArrayList<>();
    private final TabClickListener listener;

    public interface TabClickListener {
        void onTabClick(BrowserTab tab);
        void onTabClose(BrowserTab tab);
    }

    public TabsAdapter(TabClickListener listener) {
        this.listener = listener;
    }

    public void setTabs(List<BrowserTab> tabs) {
        this.tabs = new ArrayList<>(tabs);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public TabViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_browser_tab, parent, false);
        return new TabViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TabViewHolder holder, int position) {
        BrowserTab tab = tabs.get(position);
        holder.bind(tab, listener);
    }

    @Override
    public int getItemCount() {
        return tabs.size();
    }

    static class TabViewHolder extends RecyclerView.ViewHolder {
        private final CardView cardView;
        private final TextView titleText;
        private final TextView urlText;
        private final ImageView closeButton;
        private final ImageView faviconImage;
        private final View activeIndicator;

        public TabViewHolder(@NonNull View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.tab_card);
            titleText = itemView.findViewById(R.id.tab_title);
            urlText = itemView.findViewById(R.id.tab_url);
            closeButton = itemView.findViewById(R.id.tab_close);
            faviconImage = itemView.findViewById(R.id.tab_favicon);
            activeIndicator = itemView.findViewById(R.id.active_indicator);
        }

        public void bind(BrowserTab tab, TabClickListener listener) {
            titleText.setText(tab.getDisplayTitle());
            urlText.setText(tab.getUrl() != null ? tab.getUrl() : "");
            
            // Show active indicator
            activeIndicator.setVisibility(tab.isActive() ? View.VISIBLE : View.GONE);
            
            // Set favicon if available
            if (tab.getFavicon() != null) {
                faviconImage.setImageBitmap(tab.getFavicon());
            } else {
                faviconImage.setImageResource(R.drawable.ic_logo);
            }

            // Click to switch to tab
            cardView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onTabClick(tab);
                }
            });

            // Click to close tab
            closeButton.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onTabClose(tab);
                }
            });
        }
    }
}
