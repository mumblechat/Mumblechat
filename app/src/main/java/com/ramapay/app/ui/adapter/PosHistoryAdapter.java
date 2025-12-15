package com.ramapay.app.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.ramapay.app.R;
import com.ramapay.app.repository.entity.RealmPosInvoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Adapter for displaying PoS invoice history
 */
public class PosHistoryAdapter extends RecyclerView.Adapter<PosHistoryAdapter.InvoiceViewHolder>
{
    private List<RealmPosInvoice> invoices = new ArrayList<>();
    private final OnInvoiceClickListener listener;

    public interface OnInvoiceClickListener
    {
        void onInvoiceClick(RealmPosInvoice invoice);
    }

    public PosHistoryAdapter(OnInvoiceClickListener listener)
    {
        this.listener = listener;
    }

    public void setInvoices(List<RealmPosInvoice> invoices)
    {
        this.invoices = invoices != null ? invoices : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public InvoiceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType)
    {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_pos_invoice, parent, false);
        return new InvoiceViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull InvoiceViewHolder holder, int position)
    {
        RealmPosInvoice invoice = invoices.get(position);
        holder.bind(invoice);
    }

    @Override
    public int getItemCount()
    {
        return invoices.size();
    }

    class InvoiceViewHolder extends RecyclerView.ViewHolder
    {
        private final ImageView imageStatus;
        private final TextView textFiatAmount;
        private final TextView textCryptoAmount;
        private final TextView textStatus;
        private final TextView textCategory;
        private final TextView textDateTime;
        private final View cardView;

        InvoiceViewHolder(@NonNull View itemView)
        {
            super(itemView);
            imageStatus = itemView.findViewById(R.id.image_status);
            textFiatAmount = itemView.findViewById(R.id.text_fiat_amount);
            textCryptoAmount = itemView.findViewById(R.id.text_crypto_amount);
            textStatus = itemView.findViewById(R.id.text_status);
            textCategory = itemView.findViewById(R.id.text_category);
            textDateTime = itemView.findViewById(R.id.text_date_time);
            cardView = itemView.findViewById(R.id.card_invoice);
        }

        void bind(RealmPosInvoice invoice)
        {
            // Fiat amount
            String currencySymbol = getCurrencySymbol(invoice.getFiatCurrency());
            textFiatAmount.setText(currencySymbol + invoice.getFiatAmount());

            // Crypto amount
            BigDecimal cryptoDisplay = new BigDecimal(invoice.getCryptoAmount())
                    .divide(BigDecimal.TEN.pow(invoice.getTokenDecimals()), 6, RoundingMode.HALF_UP);
            textCryptoAmount.setText(cryptoDisplay.stripTrailingZeros().toPlainString() 
                    + " " + invoice.getTokenSymbol());

            // Status with color
            String status = invoice.getStatus();
            textStatus.setText(getStatusDisplayText(status));
            setStatusColor(status);

            // Category
            if (invoice.getCategory() != null && !invoice.getCategory().isEmpty())
            {
                textCategory.setVisibility(View.VISIBLE);
                textCategory.setText(invoice.getCategory());
            }
            else
            {
                textCategory.setVisibility(View.GONE);
            }

            // Date/Time
            SimpleDateFormat sdf = new SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault());
            textDateTime.setText(sdf.format(new Date(invoice.getCreatedAt())));

            // Click listener
            cardView.setOnClickListener(v -> {
                if (listener != null)
                {
                    listener.onInvoiceClick(invoice);
                }
            });
        }

        private String getStatusDisplayText(String status)
        {
            if (status == null) return "Unknown";
            
            switch (status.toUpperCase())
            {
                case "PENDING":
                    return "Pending";
                case "PAID":
                    return "Paid ✓";
                case "EXPIRED":
                    return "Expired";
                case "CANCELLED":
                    return "Cancelled";
                case "FAILED":
                    return "Failed";
                default:
                    return status;
            }
        }

        private void setStatusColor(String status)
        {
            int colorRes;
            int iconRes;
            
            if (status == null) status = "";
            
            switch (status.toUpperCase())
            {
                case "PAID":
                    colorRes = R.color.positive;
                    iconRes = R.drawable.ic_check_circle;
                    break;
                case "PENDING":
                    colorRes = R.color.brand;
                    iconRes = R.drawable.ic_pending;
                    break;
                case "EXPIRED":
                case "CANCELLED":
                    colorRes = R.color.text_secondary;
                    iconRes = R.drawable.ic_cancel;
                    break;
                case "FAILED":
                    colorRes = R.color.negative;
                    iconRes = R.drawable.ic_error;
                    break;
                default:
                    colorRes = R.color.text_secondary;
                    iconRes = R.drawable.ic_pending;
            }
            
            textStatus.setTextColor(ContextCompat.getColor(itemView.getContext(), colorRes));
            imageStatus.setImageResource(iconRes);
            imageStatus.setColorFilter(ContextCompat.getColor(itemView.getContext(), colorRes));
        }

        private String getCurrencySymbol(String currency)
        {
            if (currency == null) return "";
            switch (currency.toUpperCase())
            {
                case "INR": return "₹";
                case "USD": return "$";
                case "EUR": return "€";
                case "GBP": return "£";
                case "AED": return "د.إ";
                case "SGD": return "S$";
                default: return currency + " ";
            }
        }
    }
}
