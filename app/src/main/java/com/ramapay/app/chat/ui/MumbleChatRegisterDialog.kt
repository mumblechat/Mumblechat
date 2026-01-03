package com.ramapay.app.chat.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import com.ramapay.app.R

/**
 * Beautiful registration dialog for MumbleChat.
 * 
 * Shows features and handles the registration flow:
 * 1. User sees dialog with features
 * 2. User clicks "Register"
 * 3. Dialog shows loading state
 * 4. Transaction is created and sent
 * 5. Success/failure callback
 */
class MumbleChatRegisterDialog : DialogFragment() {

    interface RegistrationCallback {
        fun onRegisterClicked()
        fun onLaterClicked()
    }

    private var callback: RegistrationCallback? = null
    private var loadingOverlay: FrameLayout? = null
    private var loadingText: TextView? = null

    companion object {
        const val TAG = "MumbleChatRegisterDialog"

        fun newInstance(): MumbleChatRegisterDialog {
            return MumbleChatRegisterDialog()
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        // Try to attach to parent fragment first, then activity
        callback = parentFragment as? RegistrationCallback
            ?: context as? RegistrationCallback
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            window?.requestFeature(Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_mumblechat_register, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadingOverlay = view.findViewById(R.id.loadingOverlay)
        loadingText = view.findViewById(R.id.loadingText)

        view.findViewById<MaterialButton>(R.id.buttonRegister)?.setOnClickListener {
            callback?.onRegisterClicked()
        }

        view.findViewById<MaterialButton>(R.id.buttonLater)?.setOnClickListener {
            callback?.onLaterClicked()
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        // Make dialog wider
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /**
     * Show loading state while transaction is being processed.
     */
    fun showLoading(message: String = getString(R.string.registering)) {
        loadingOverlay?.visibility = View.VISIBLE
        loadingText?.text = message
        isCancelable = false
    }

    /**
     * Hide loading state.
     */
    fun hideLoading() {
        loadingOverlay?.visibility = View.GONE
        isCancelable = true
    }

    /**
     * Update loading message.
     */
    fun updateLoadingMessage(message: String) {
        loadingText?.text = message
    }

    override fun onDetach() {
        super.onDetach()
        callback = null
    }
}
