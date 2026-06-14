package com.voiceinput.service

import android.app.Notification
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import com.voiceinput.data.model.NotificationData
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationListenerService : NotificationListenerService() {
    
    private val TAG = "NotificationListener"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var backgroundForwarder: BackgroundNotificationForwarder
    
    companion object {
        var instance: NotificationListenerService? = null
        var onNotificationReceived: ((NotificationData) -> Unit)? = null
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        backgroundForwarder = BackgroundNotificationForwarder(applicationContext)
        Log.i(TAG, "通知监听服务已启动")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        if (::backgroundForwarder.isInitialized) {
            backgroundForwarder.release()
        }
        serviceScope.cancel()
        Log.i(TAG, "通知监听服务已停止")
    }
    
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val notification = sbn.notification ?: return
            val extras = notification.extras ?: return
            
            val packageName = sbn.packageName
            val appName = getAppName(packageName)
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
            val conversationTitle = extras
                .getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                ?.toString()
                ?: ""
            val importance = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val manager = getSystemService(NotificationManager::class.java)
                manager?.getNotificationChannel(notification.channelId)?.importance
                    ?: NotificationManager.IMPORTANCE_DEFAULT
            } else {
                @Suppress("DEPRECATION")
                notification.priority
            }

            if (packageName == applicationContext.packageName) return
            
            // 获取图标
            val icon = notification.smallIcon?.loadDrawable(this)
            val iconBase64 = icon?.let { drawableToBase64(it) }
            
            val notificationData = NotificationData(
                appName = appName,
                appPackage = packageName,
                title = title,
                text = text.ifBlank { bigText },
                timestamp = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis(),
                notificationKey = sbn.key ?: "",
                channelId = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    notification.channelId ?: ""
                } else {
                    ""
                },
                groupKey = sbn.groupKey ?: "",
                category = notification.category ?: "",
                subText = subText,
                bigText = bigText,
                conversationTitle = conversationTitle,
                postTime = sbn.postTime,
                isOngoing = sbn.isOngoing,
                isClearable = sbn.isClearable,
                importance = importance,
                icon = iconBase64
            )
            
            Log.d(TAG, "收到通知: $appName - $title")
            val handler = onNotificationReceived
            if (handler != null) {
                handler.invoke(notificationData)
            } else {
                serviceScope.launch {
                    backgroundForwarder.forwardOrStore(notificationData)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "处理通知错误: ${e.message}")
        }
    }
    
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // 通知被移除时的处理（可选）
    }
    
    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }
    
    private fun drawableToBase64(drawable: Drawable): String? {
        return try {
            val bitmap = if (drawable is BitmapDrawable) {
                drawable.bitmap
            } else {
                val bitmap = Bitmap.createBitmap(
                    drawable.intrinsicWidth,
                    drawable.intrinsicHeight,
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap
            }
            
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            val byteArray = outputStream.toByteArray()
            Base64.encodeToString(byteArray, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "图标转换错误: ${e.message}")
            null
        }
    }

}
