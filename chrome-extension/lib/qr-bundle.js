/**
 * QR Code Bundle for popup
 */
import qrcode from 'qrcode-generator';

// Export to window for popup.js to use
window.qrcode = qrcode;

export { qrcode };
