package me.kavishdevar.librepods.services

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

data class VoipCallNotification(
    val key: String,
    val packageName: String,
    val answer: PendingIntent?,
    val decline: PendingIntent?,
)

/** Notification actions are the supported way to answer third-party VoIP calls. */
class VoipCallNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        activeNotifications?.forEach(::updateNotification)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        updateNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        VoipCallNotifications.remove(sbn.key)
    }

    private fun updateNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification
        val extras = notification.extras ?: return
        val answer = extras.getParcelableCompat(Notification.EXTRA_ANSWER_INTENT)
            ?: findAction(notification, ANSWER_LABELS)
        val decline = extras.getParcelableCompat(Notification.EXTRA_DECLINE_INTENT)
            ?: findAction(notification, DECLINE_LABELS)

        val callType = if (android.os.Build.VERSION.SDK_INT >= 34) {
            extras.getInt(Notification.EXTRA_CALL_TYPE, Notification.CallStyle.CALL_TYPE_UNKNOWN)
        } else {
            Notification.CallStyle.CALL_TYPE_UNKNOWN
        }
        val incomingCallStyle = callType == Notification.CallStyle.CALL_TYPE_INCOMING
        val knownVoipApp = sbn.packageName in VOIP_PACKAGES
        val hasIncomingActions = answer != null && decline != null

        if (knownVoipApp && (incomingCallStyle || hasIncomingActions)) {
            Log.i(TAG, "Incoming VoIP call notification from ${sbn.packageName}")
            VoipCallNotifications.publish(
                VoipCallNotification(sbn.key, sbn.packageName, answer, decline)
            )
        } else {
            VoipCallNotifications.remove(sbn.key)
        }
    }

    private fun findAction(notification: Notification, labels: Set<String>): PendingIntent? =
        notification.actions
            ?.firstOrNull { it.title?.toString()?.trim()?.lowercase() in labels }
            ?.actionIntent

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> android.os.Bundle.getParcelableCompat(key: String): T? =
        if (android.os.Build.VERSION.SDK_INT >= 33) getParcelable(key, T::class.java)
        else getParcelable(key) as? T

    companion object {
        private const val TAG = "VoipCallListener"
        private val VOIP_PACKAGES = setOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "com.whatsapp",
            "com.whatsapp.w4b",
        )
        private val ANSWER_LABELS = setOf("answer", "accept", "pick up", "ответить", "принять")
        private val DECLINE_LABELS = setOf("decline", "reject", "hang up", "отклонить", "сбросить")
    }
}

object VoipCallNotifications {
    @Volatile
    private var current: VoipCallNotification? = null
    @Volatile
    private var observer: ((VoipCallNotification?) -> Unit)? = null

    fun observe(callback: (VoipCallNotification?) -> Unit) {
        observer = callback
        callback(current)
    }

    fun clearObserver(callback: (VoipCallNotification?) -> Unit) {
        if (observer === callback) observer = null
    }

    fun publish(call: VoipCallNotification) {
        current = call
        observer?.invoke(call)
    }

    fun remove(key: String) {
        if (current?.key == key) {
            current = null
            observer?.invoke(null)
        }
    }
}
