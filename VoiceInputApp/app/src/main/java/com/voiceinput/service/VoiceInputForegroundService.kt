package com.voiceinput.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.voiceinput.MainActivity
import com.voiceinput.R
import com.voiceinput.data.ConfigManager
import com.voiceinput.data.ForegroundNotificationState

class VoiceInputForegroundService : Service() {
    private lateinit var configManager: ConfigManager

    override fun onCreate() {
        super.onCreate()
        configManager = ConfigManager(this)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_FORWARDING -> {
                configManager.saveNotificationEnabled(false)
                notifyForwardingStateChanged()
            }
            ACTION_RESUME_FORWARDING -> {
                configManager.saveNotificationEnabled(true)
                notifyForwardingStateChanged()
            }
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag()
        )

        val notificationEnabled = configManager.isNotificationEnabled()
        val target = configManager.getLastTargetDevice()?.deviceName
        val connectionStatus = configManager.getForegroundConnectionStatus()
        val notificationState = ForegroundNotificationState.from(
            forwardingEnabled = notificationEnabled,
            connected = connectionStatus.connected,
            connectedDeviceName = connectionStatus.deviceName,
            targetDeviceName = target
        )
        val actionIntent = Intent(this, VoiceInputForegroundService::class.java).apply {
            action = if (notificationEnabled) ACTION_PAUSE_FORWARDING else ACTION_RESUME_FORWARDING
        }
        val actionPendingIntent = PendingIntent.getService(
            this,
            if (notificationEnabled) 1 else 2,
            actionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag()
        )
        val switchTargetIntent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_SETTINGS
            putExtra(MainActivity.EXTRA_SETTINGS_SECTION, MainActivity.SETTINGS_SECTION_DEVICES)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val switchTargetPendingIntent = PendingIntent.getActivity(
            this,
            3,
            switchTargetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag()
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("语传正在同步")
            .setContentText(notificationState.statusText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationState.statusText))
            .setContentIntent(pendingIntent)
            .addAction(
                0,
                notificationState.toggleActionLabel,
                actionPendingIntent
            )
            .addAction(
                0,
                "切换目标电脑",
                switchTargetPendingIntent
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "语传常驻同步",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持语传在后台运行，用于同步输入和通知"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun notifyForwardingStateChanged() {
        sendBroadcast(Intent(ACTION_NOTIFICATION_FORWARDING_CHANGED).setPackage(packageName))
    }

    companion object {
        const val ACTION_NOTIFICATION_FORWARDING_CHANGED =
            "com.voiceinput.action.NOTIFICATION_FORWARDING_CHANGED"
        private const val CHANNEL_ID = "voice_input_foreground"
        private const val NOTIFICATION_ID = 2101
        private const val ACTION_PAUSE_FORWARDING = "com.voiceinput.action.PAUSE_NOTIFICATION_FORWARDING"
        private const val ACTION_RESUME_FORWARDING = "com.voiceinput.action.RESUME_NOTIFICATION_FORWARDING"

        fun start(context: Context) {
            val intent = Intent(context, VoiceInputForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceInputForegroundService::class.java))
        }

        private fun pendingIntentImmutableFlag(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    }
}
