package com.ramapay.app.ui;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;

import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ramapay.app.R;
import com.ramapay.app.repository.entity.RealmPosInvoice;
import com.ramapay.app.ui.adapter.PosHistoryAdapter;
import com.ramapay.app.viewmodel.PosViewModel;

import java.util.List;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Activity to display PoS transaction history
 * Shows all invoices with their status (pending, paid, cancelled, expired)
 */
@AndroidEntryPoint
public class PosHistoryActivity extends BaseActivity implements PosHistoryAdapter.OnInvoiceClickListener
{
    private PosViewModel viewModel;
    private RecyclerView recyclerView;
    private PosHistoryAdapter adapter;
    private LinearLayout layoutEmpty;
    private View progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pos_history);

        initViews();
        initViewModel();
        loadHistory();
    }

    private void initViews()
    {
        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.pos_history_title);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null)
        {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }

        recyclerView = findViewById(R.id.recycler_history);
        layoutEmpty = findViewById(R.id.text_empty);
        progressBar = findViewById(R.id.progress_bar);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PosHistoryAdapter(this);
        recyclerView.setAdapter(adapter);
    }

    private void initViewModel()
    {
        viewModel = new ViewModelProvider(this).get(PosViewModel.class);

        viewModel.invoiceHistory().observe(this, this::onHistoryLoaded);
        viewModel.defaultWallet().observe(this, wallet -> {
            if (wallet != null)
            {
                viewModel.loadInvoiceHistory();
            }
        });
    }

    private void loadHistory()
    {
        progressBar.setVisibility(View.VISIBLE);
        layoutEmpty.setVisibility(View.GONE);
        recyclerView.setVisibility(View.GONE);
        
        viewModel.prepare();
    }

    private void onHistoryLoaded(List<RealmPosInvoice> invoices)
    {
        progressBar.setVisibility(View.GONE);

        if (invoices == null || invoices.isEmpty())
        {
            layoutEmpty.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        }
        else
        {
            layoutEmpty.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            adapter.setInvoices(invoices);
        }
    }

    @Override
    public void onInvoiceClick(RealmPosInvoice invoice)
    {
        // TODO: Show invoice details dialog or navigate to detail screen
        showInvoiceDetails(invoice);
    }

    private void showInvoiceDetails(RealmPosInvoice invoice)
    {
        // Show a dialog with invoice details
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        builder.setTitle("Invoice Details");

        StringBuilder details = new StringBuilder();
        details.append("Invoice ID: ").append(invoice.getInvoiceId()).append("\n\n");
        details.append("Amount: ").append(viewModel.getCurrencySymbol(invoice.getFiatCurrency()))
                .append(invoice.getFiatAmount()).append("\n");
        
        java.math.BigDecimal cryptoDisplay = new java.math.BigDecimal(invoice.getCryptoAmount())
                .divide(java.math.BigDecimal.TEN.pow(invoice.getTokenDecimals()), 6, java.math.RoundingMode.HALF_UP);
        details.append("Crypto: ").append(cryptoDisplay.stripTrailingZeros().toPlainString())
                .append(" ").append(invoice.getTokenSymbol()).append("\n\n");
        
        details.append("Status: ").append(invoice.getStatus()).append("\n");
        
        if (invoice.getCategory() != null && !invoice.getCategory().isEmpty())
        {
            details.append("Category: ").append(invoice.getCategory()).append("\n");
        }
        
        if (invoice.getNote() != null && !invoice.getNote().isEmpty())
        {
            details.append("Note: ").append(invoice.getNote()).append("\n");
        }
        
        details.append("\nCreated: ").append(formatTimestamp(invoice.getCreatedAt()));
        
        if (invoice.getPaidAt() > 0)
        {
            details.append("\nPaid: ").append(formatTimestamp(invoice.getPaidAt()));
        }
        
        if (invoice.getTxHash() != null && !invoice.getTxHash().isEmpty())
        {
            details.append("\n\nTX: ").append(invoice.getTxHash().substring(0, 20)).append("...");
        }

        builder.setMessage(details.toString());
        builder.setPositiveButton("OK", null);
        
        if (invoice.getTxHash() != null && !invoice.getTxHash().isEmpty())
        {
            builder.setNeutralButton("View on Explorer", (dialog, which) -> {
                String url = "https://ramascan.com/tx/" + invoice.getTxHash();
                android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, 
                        android.net.Uri.parse(url));
                startActivity(intent);
            });
        }

        builder.show();
    }

    private String formatTimestamp(long timestamp)
    {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", 
                java.util.Locale.getDefault());
        return sdf.format(new java.util.Date(timestamp));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        if (item.getItemId() == android.R.id.home)
        {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
