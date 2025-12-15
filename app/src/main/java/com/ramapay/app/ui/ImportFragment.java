package com.ramapay.app.ui;

import android.text.TextWatcher;
import android.view.View;

import androidx.fragment.app.Fragment;

import com.ramapay.app.widget.LayoutCallbackListener;

/**
 * Created by JB on 5/01/2022.
 */
public abstract class ImportFragment extends Fragment implements View.OnClickListener, TextWatcher, LayoutCallbackListener
{
    public void comeIntoFocus() { }
    public void leaveFocus() { }
}
