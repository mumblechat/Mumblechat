package com.ramapay.app.chat.notification

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ramapay.app.R
import com.ramapay.app.chat.data.entity.MessageEntity
import com.ramapay.app.ui.HomeActivity
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper class for showing chat message notifications.
 * Respects user's notification preferences.
 * Only shows notifications when app is in background.
 */
@Singleton
class ChatNotificationHelper @Inject constructor(
    private val context: Context
) : Application.ActivityLifecycleCallbacks {
    companion object {
        const val CHANNEL_ID_MESSAGES = "mumblechat_messages"
        const val CHANNEL_ID_GROUPS = "mumblechat_groups"
        const val CHANNEL_NAME_MESSAGES = "Message Notifications"
        const val CHANNEL_NAME_GROUPS = "Group Notifications"
        
        private const val NOTIFICATION_ID_BASE = 10000
        private const val MAX_NOTIFICATIONS = 50
        
        // Preference keys
        private const val PREFS_NAME = "mumblechat_prefs"
        private const val KEY_MESSAGE_NOTIFICATIONS = "message_notifications"
        private const val KEY_GROUP_NOTIFICATIONS = "group_notifications"
        private const val KEY_NOTIFICATION_SOUND = "notification_sound"
        private const val KEY_NOTIFICATION_VIBRATION = "notification_vibration"
        private const val KEY_SHOW_PREVIEW = "show_preview"
    }
    
    private val notificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    
    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    // Track notification IDs per conversation
    private val notificationIds = mutableMapOf<String, Int>()
    private var nextNotificationId = NOTIFICATION_ID_BASE
    
    // Track if app is in foreground
    private var activeActivities = 0
    private val isAppInForeground: Boolean
        get() = activeActivities > 0
    
    init {
        createNotificationChannels()
        // Register lifecycle callbacks to track foreground state
        if (context is Application) {
            context.registerActivityLifecycleCallbacks(this)
        } else if (context.applicationContext is Application) {
            (context.applicationContext as Application).registerActivityLifecycleCallbacks(this)
        }
        Timber.d("ChatNotificationHelper: Initialized")
    }
    
    // ActivityLifecycleCallbacks
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {
        activeActivities++
        Timber.d("ChatNotificationHelper: Activity started, active count: $activeActivities")
    }
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {
        activeActivities--
        Timber.d("ChatNotificationHelper: Activity stopped, active count: $activeActivities")
    }
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
    
    /**
     * Create notification channels (required for Android 8.0+)
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Messages channel
            val messagesChannel = NotificationChannel(
                CHANNEL_ID_MESSAGES,
                CHANNEL_NAME_MESSAGES,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for new messages"
                enableLights(true)
                enableVibration(true)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
            
            // Groups channel
            val groupsChannel = NotificationChannel(
                CHANNEL_ID_GROUPS,
                CHANNEL_NAME_GROUPS,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for group messages"
                enableLights(true)
                enableVibration(true)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
            
            notificationManager.createNotificationChannel(messagesChannel)
            notificationManager.createNotificationChannel(groupsChannel)
            
            Timber.d("ChatNotificationHelper: Notification channels created")
        }
    }
    
    /**
     * Show notification for an incoming message
     */
    fun showMessageNotification(
        message: MessageEntity,
        senderName: String? = null,
        conversationId: String
    ) {
        Timber.d("ChatNotificationHelper: showMessageNotification called for message from ${message.senderAddress}")
        
        // Check if message notifications are enabled
        if (!isMessageNotificationsEnabled()) {
            Timber.d("ChatNotificationHelper: Message notifications disabled by user")
            return
        }
        
        // Check if group notifications are enabled (for group messages)
        if (message.groupId != null && !isGroupNotificationsEnabled()) {
            Timber.d("ChatNotificationHelper: Group notifications disabled by user")
            return
        }
        
        // Check notification permission on Android 13+
        if (!hasNotificationPermission()) {
            Timber.w("ChatNotificationHelper: POST_NOTIFICATIONS permission not granted")
            return
        }
        
        // Note: We show notifications even in foreground for now
        // In production, you might want to use in-app notifications instead
        Timber.d("ChatNotificationHelper: App in foreground: $isAppInForeground")
        
        val channelId = if (message.groupId != null) CHANNEL_ID_GROUPS else CHANNEL_ID_MESSAGES
        val notificationId = getNotificationIdForConversation(conversationId)
        
        // Build sender display name
        val displayName = senderName ?: shortenAddress(message.senderAddress)
        
        // Build message content
        val contentText = if (shouldShowPreview()) {
            message.content.take(100)
        } else {
            "New message"
        }
        
        // Create intent for when notification is tapped
        val intent = Intent(context, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to_chat", true)
            putExtra("conversation_id", conversationId)
            putExtra("peer_address", message.senderAddress)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Build notification
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notifications)
            .setContentTitle(displayName)
            .setContentText(contentText)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        
        // Add sound if enabled
        if (isSoundEnabled()) {
            builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
        } else {
            builder.setSound(null)
        }
        
        // Add vibration if enabled
        if (isVibrationEnabled()) {
            builder.setVibrate(longArrayOf(0, 300, 100, 300))
            triggerVibration()
        } else {
            builder.setVibrate(null)
        }
        
        // Show notification
        try {
            notificationManager.notify(notificationId, builder.build())
            Timber.d("ChatNotificationHelper: Notification shown for message ${message.id}")
        } catch (e: Exception) {
            Timber.e(e, "ChatNotificationHelper: Failed to show notification")
        }
    }
    
    /**
     * Cancel notification for a conversation (when user opens the chat)
     */
    fun cancelNotification(conversationId: String) {
        val notificationId = notificationIds[conversationId] ?: return
        notificationManager.cancel(notificationId)
        Timber.d("ChatNotificationHelper: Cancelled notification for $conversationId")
    }
    
    /**
     * Cancel all chat notifications
     */
    fun cancelAllNotifications() {
        notificationIds.values.forEach { id ->
            notificationManager.cancel(id)
        }
        notificationIds.clear()
        Timber.d("ChatNotificationHelper: Cancelled all notifications")
    }
    
    // ============ Preference Getters ============
    
    fun isMessageNotificationsEnabled(): Boolean {
        return prefs.getBoolean(KEY_MESSAGE_NOTIFICATIONS, true)
    }
    
    fun isGroupNotificationsEnabled(): Boolean {
        return prefs.getBoolean(KEY_GROUP_NOTIFICATIONS, true)
    }
    
    fun isSoundEnabled(): Boolean {
        return prefs.getBoolean(KEY_NOTIFICATION_SOUND, true)
    }
    
    fun isVibrationEnabled(): Boolean {
        return prefs.getBoolean(KEY_NOTIFICATION_VIBRATION, true)
    }
    
    fun shouldShowPreview(): Boolean {
        return prefs.getBoolean(KEY_SHOW_PREVIEW, true)
    }
    
    // ============ Preference Setters ============
    
    fun setMessageNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MESSAGE_NOTIFICATIONS, enabled).apply()
    }
    
    fun setGroupNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GROUP_NOTIFICATIONS, enabled).apply()
    }
    
    fun setSoundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATION_SOUND, enabled).apply()
    }
    
    fun setVibrationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATION_VIBRATION, enabled).apply()
    }
    
    fun setShowPreview(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_PREVIEW, enabled).apply()
    }
    
    // ============ Private Helpers ============
    
    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
    
    private fun getNotificationIdForConversation(conversationId: String): Int {
        return notificationIds.getOrPut(conversationId) {
            val id = nextNotificationId++
            if (nextNotificationId > NOTIFICATION_ID_BASE + MAX_NOTIFICATIONS) {
                nextNotificationId = NOTIFICATION_ID_BASE
            }
            id
        }
    }
    
    private fun shortenAddress(address: String): String {
        return if (address.length > 10) {
            "${address.take(6)}...${address.takeLast(4)}"
        } else {
            address
        }
    }
    
    private fun triggerVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(300)
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "ChatNotificationHelper: Failed to trigger vibration")
        }
    }
}
