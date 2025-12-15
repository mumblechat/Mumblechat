package com.ramapay.app.ui;

import android.content.Intent;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;

import com.ramapay.app.R;
import com.ramapay.app.entity.BackupTokenCallback;
import com.ramapay.app.entity.ContractLocator;
import com.ramapay.app.entity.FragmentMessenger;

import org.jetbrains.annotations.NotNull;

import java.util.List;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class BaseFragment extends Fragment implements Toolbar.OnMenuItemClickListener,
    BackupTokenCallback
{
    private Toolbar toolbar;
    private TextView toolbarTitle;
    private TextView toolbarSubtitle;
    private View toolbarSubtitleLayout;
    private String fullAddress;

    private void initToolbar(View view)
    {
        toolbar = view.findViewById(R.id.toolbar);
        toolbarTitle = toolbar.findViewById(R.id.toolbar_title);
        toolbarSubtitle = toolbar.findViewById(R.id.toolbar_subtitle);
        toolbarSubtitleLayout = toolbar.findViewById(R.id.toolbar_subtitle_layout);

        toolbar.setOnClickListener(this::onToolbarClicked);
    }

    protected void toolbar(View view)
    {
        if (view != null) initToolbar(view);
    }

    protected void toolbar(View view, int title, int menuResId)
    {
        initToolbar(view);
        setToolbarTitle(title);
        setToolbarMenu(menuResId);
    }

    protected void toolbar(View view, int menuResId)
    {
        initToolbar(view);
        setToolbarMenu(menuResId);
    }

    protected void toolbar(View view, int menuResId, Toolbar.OnMenuItemClickListener listener)
    {
        initToolbar(view);
        setToolbarMenu(menuResId);
        setToolbarMenuItemClickListener(listener);
    }

    protected void toolbar(View view, int title, int menuResId, Toolbar.OnMenuItemClickListener listener)
    {
        initToolbar(view);
        setToolbarTitle(title);
        setToolbarMenu(menuResId);
        setToolbarMenuItemClickListener(listener);
    }

    protected void setToolbarTitle(String title)
    {
        if (toolbarTitle != null)
        {
            toolbarTitle.setText(title);
        }
    }

    protected void setToolbarSubtitle(String subtitle)
    {
        setToolbarSubtitle(subtitle, false);
    }
    
    protected void setToolbarSubtitle(String subtitle, boolean preserveFullAddress)
    {
        if (toolbarSubtitle != null && toolbarSubtitleLayout != null)
        {
            if (subtitle != null && !subtitle.isEmpty())
            {
                toolbarSubtitle.setText(subtitle);
                toolbarSubtitleLayout.setVisibility(View.VISIBLE);
                // Only set fullAddress if not preserving (i.e., not called from setToolbarSubtitleWithFullAddress)
                if (!preserveFullAddress)
                {
                    this.fullAddress = subtitle;
                }
                
                // Add copy functionality
                View copyIcon = toolbar.findViewById(R.id.toolbar_copy_icon);
                if (copyIcon != null && copyIcon instanceof android.widget.ImageView)
                {
                    copyIcon.setOnClickListener(v -> {
                        String addressToCopy = fullAddress != null ? fullAddress : subtitle;
                        android.content.ClipboardManager clipboard = 
                            (android.content.ClipboardManager) requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                        android.content.ClipData clip = android.content.ClipData.newPlainText("address", addressToCopy);
                        if (clipboard != null)
                        {
                            clipboard.setPrimaryClip(clip);
                            // Change icon to checkmark for visual feedback
                            ((android.widget.ImageView) copyIcon).setImageResource(R.drawable.ic_correct);
                            android.widget.Toast.makeText(requireContext(), R.string.copied_to_clipboard, android.widget.Toast.LENGTH_SHORT).show();
                            
                            // Reset icon back to copy after 2 seconds
                            copyIcon.postDelayed(() -> {
                                ((android.widget.ImageView) copyIcon).setImageResource(R.drawable.ic_content_copy);
                            }, 2000);
                        }
                    });
                }
            }
            else
            {
                toolbarSubtitleLayout.setVisibility(View.GONE);
                this.fullAddress = null;
            }
        }
    }
    
    public void setToolbarSubtitleWithFullAddress(String displayAddress, String fullAddress)
    {
        this.fullAddress = fullAddress;
        setToolbarSubtitle(displayAddress, true);
    }

    protected void setToolbarMenuItemClickListener(Toolbar.OnMenuItemClickListener listener)
    {
        toolbar.setOnMenuItemClickListener(listener);
    }

    protected void setToolbarTitle(int title)
    {
        setToolbarTitle(getString(title));
    }

    protected void setToolbarMenu(int menuRes)
    {
        toolbar.inflateMenu(menuRes);
    }

    public Toolbar getToolbar()
    {
        return toolbar;
    }

    @Override
    public boolean onMenuItemClick(MenuItem menuItem)
    {
        return false;
    }

    public void comeIntoFocus()
    {
        //
    }

    public void leaveFocus()
    {
        //
    }

    public void softKeyboardVisible()
    {
    }

    public void softKeyboardGone()
    {
    }

    public void onItemClick(String url)
    {
    }

    public void onToolbarClicked(View view)
    {

    }

    public void signalPlayStoreUpdate(int updateVersion)
    {
    }

    public void signalExternalUpdate(String updateVersion)
    {
    }

    public void backupSeedSuccess(boolean hasNoLock)
    {
    }

    public void storeWalletBackupTime(String backedUpKey)
    {
    }

    public void resetTokens()
    {
    }

    public void resetTransactions()
    {
    }

    public void gotCameraAccess(@NotNull String[] permissions, int[] grantResults)
    {
    }

    public void gotGeoAccess(@NotNull String[] permissions, int[] grantResults)
    {
    }

    public void gotFileAccess(@NotNull String[] permissions, int[] grantResults)
    {
    }

    public void handleQRCode(int resultCode, Intent data, FragmentMessenger messenger)
    {
    }

    public void pinAuthorisation(boolean gotAuth)
    {
    }

    public void switchNetworkAndLoadUrl(long chainId, String url)
    {
    }

    public void scrollToTop()
    {
    }

    public void addedToken(List<ContractLocator> tokenContracts)
    {
    }

    public void setImportFilename(String fName)
    {
    }

    public void backPressed()
    {
    }
}
