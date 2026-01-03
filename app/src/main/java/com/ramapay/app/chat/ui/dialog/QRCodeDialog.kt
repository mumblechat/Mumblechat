package com.ramapay.app.chat.ui.dialog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.ramapay.app.R
import com.ramapay.app.databinding.DialogQrCodeBinding

/**
 * Dialog to display a QR code for sharing wallet address.
 */
class QRCodeDialog : DialogFragment() {

    private var _binding: DialogQrCodeBinding? = null
    private val binding get() = _binding!!

    private var address: String = ""
    private var title: String = ""
    private var subtitle: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        arguments?.let {
            address = it.getString(ARG_ADDRESS, "")
            title = it.getString(ARG_TITLE, "")
            subtitle = it.getString(ARG_SUBTITLE, "")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogQrCodeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        generateQRCode()
        setupClickListeners()
    }

    private fun setupUI() {
        binding.textTitle.text = title
        binding.textSubtitle.text = subtitle
        binding.textAddress.text = formatAddress(address)
    }

    private fun generateQRCode() {
        if (address.isEmpty()) return

        try {
            val hints = hashMapOf<EncodeHintType, Any>()
            hints[EncodeHintType.MARGIN] = 1
            hints[EncodeHintType.CHARACTER_SET] = "UTF-8"

            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(address, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints)

            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            // Use MumbleChat accent color for QR code
            val qrColor = requireContext().getColor(R.color.mumblechat_accent)
            val bgColor = Color.WHITE

            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) qrColor else bgColor)
                }
            }

            binding.imageQrCode.setImageBitmap(bitmap)

        } catch (e: Exception) {
            Toast.makeText(context, "Failed to generate QR code", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupClickListeners() {
        binding.buttonClose.setOnClickListener {
            dismiss()
        }

        binding.buttonCopy.setOnClickListener {
            copyToClipboard()
        }

        binding.buttonShare.setOnClickListener {
            shareAddress()
        }
        
        binding.textAddress.setOnClickListener {
            copyToClipboard()
        }
    }

    private fun copyToClipboard() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Wallet Address", address)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, getString(R.string.address_copied), Toast.LENGTH_SHORT).show()
    }

    private fun shareAddress() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_address))
            putExtra(Intent.EXTRA_TEXT, "Chat with me on MumbleChat!\n\nWallet: $address")
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_address)))
    }

    private fun formatAddress(address: String): String {
        return if (address.length > 16) {
            "${address.take(8)}...${address.takeLast(8)}"
        } else {
            address
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_ADDRESS = "address"
        private const val ARG_TITLE = "title"
        private const val ARG_SUBTITLE = "subtitle"
        private const val QR_SIZE = 512

        fun newInstance(
            address: String,
            title: String = "Your QR Code",
            subtitle: String = "Scan to chat with me"
        ): QRCodeDialog {
            return QRCodeDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_ADDRESS, address)
                    putString(ARG_TITLE, title)
                    putString(ARG_SUBTITLE, subtitle)
                }
            }
        }
    }
}
