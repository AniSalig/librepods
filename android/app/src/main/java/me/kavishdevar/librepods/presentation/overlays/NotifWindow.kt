/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.presentation.overlays

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.AirPodsNotifications
import me.kavishdevar.librepods.data.Battery
import me.kavishdevar.librepods.data.BatteryComponent
import me.kavishdevar.librepods.services.ServiceManager

/**
 * Same idea as [IslandWindow], but instead of drawing an overlay it posts an Android 16+
 * "Live Update" notification. The status bar chip shows the battery as "##%|##%" (left|right).
 *
 * Requires: compileSdk 36, POST_NOTIFICATIONS granted at runtime, and
 * <uses-permission android:name="android.permission.POST_PROMOTED_NOTIFICATIONS" /> in the manifest.
 * On older Android versions a normal ongoing notification is shown instead of a chip.
 */
class NotifWindow(private val context: Context) {

    companion object {
        private const val TAG = "NotifWindow"
        private const val CHANNEL_ID = "island_live_update"
        private const val NOTIF_ID = 4242
        private const val AUTO_CLOSE_MS = 8000L

        private const val ACTION_TAKE_OVER = "me.kavishdevar.librepods.ISLAND_TAKE_OVER"
        private const val ACTION_DISMISSED = "me.kavishdevar.librepods.ISLAND_DISMISSED"

        private val SMALL_ICON = R.drawable.airpods
    }

    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var autoCloseRunnable: Runnable? = null

    private var isClosing = false
    private var receiverRegistered = false

    // State needed to rebuild the notification when the battery changes
    private var deviceName = ""
    private var type = IslandType.CONNECTED
    private var reversed = false
    private var otherDeviceName: String? = null
    private var leftLevel = 0
    private var rightLevel = 0

    var isVisible = false
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                AirPodsNotifications.BATTERY_DATA -> {
                    val batteryList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayListExtra("data", Battery::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableArrayListExtra("data")
                    }
                    updateBattery(batteryList)
                }
                AirPodsNotifications.DISCONNECT_RECEIVERS, ACTION_DISMISSED -> close()
                ACTION_TAKE_OVER -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        ServiceManager.getService()?.takeOver("reverse")
                    }
                    close()
                }
            }
        }
    }

    /**
     * Drop-in replacement for IslandWindow.show().
     *
     * Left/right levels are read from the service, like the original. [batteryPercentage] is only
     * used as a fallback for both sides when the service has no battery data yet.
     * [context] is accepted for compatibility; the constructor's context is what's actually used.
     */
    @Suppress("UNUSED_PARAMETER")
    fun show(
        name: String,
        batteryPercentage: Int,
        context: Context,
        type: IslandType = IslandType.CONNECTED,
        reversed: Boolean = false,
        otherDeviceName: String? = null
    ) {
        val service = ServiceManager.getService()
        if (service?.islandOpen == true) return
        service?.islandOpen = true

        if (!notificationManager.areNotificationsEnabled()) {
            Log.e(TAG, "Notifications are disabled, can't show live update")
            service?.islandOpen = false
            return
        }

        this.deviceName = name
        this.type = type
        this.reversed = reversed
        this.otherDeviceName = otherDeviceName

        val batteryList = service?.getBattery()
        leftLevel = batteryList?.find { it.component == BatteryComponent.LEFT }?.level ?: 0
        rightLevel = batteryList?.find { it.component == BatteryComponent.RIGHT }?.level ?: 0
        if (leftLevel <= 0 && rightLevel <= 0 && batteryPercentage > 0) {
            leftLevel = batteryPercentage
            rightLevel = batteryPercentage
        }

        if (type == IslandType.MOVED_TO_OTHER_DEVICE && otherDeviceName.isNullOrEmpty()) {
            Log.e(TAG, "Other device name is null or empty for MOVED_TO_OTHER_DEVICE type")
        }

        createChannel()

        val filter = IntentFilter(AirPodsNotifications.BATTERY_DATA).apply {
            addAction(AirPodsNotifications.DISCONNECT_RECEIVERS)
            addAction(ACTION_TAKE_OVER)
            addAction(ACTION_DISMISSED)
        }
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }

        isVisible = true
        post()
        service?.sendBatteryBroadcast()
        resetAutoCloseTimer()
    }

    private fun updateBattery(batteryList: ArrayList<Battery>?) {
        if (batteryList.isNullOrEmpty()) return

        leftLevel = batteryList.find { it.component == BatteryComponent.LEFT }?.level ?: 0
        rightLevel = batteryList.find { it.component == BatteryComponent.RIGHT }?.level ?: 0

        if (isVisible) post()
    }

    /** "##%|##%", with "--" for a bud whose level is unknown. */
    private fun batteryText(): String {
        fun fmt(level: Int) = if (level > 0) "$level%" else "--"
        return "${fmt(leftLevel)} ${fmt(rightLevel)}"
    }

    private fun showsBattery() = !(type == IslandType.MOVED_TO_OTHER_DEVICE && !reversed)

    private fun statusText(): String = when (type) {
        IslandType.CONNECTED -> context.getString(R.string.island_connected_text)
        IslandType.TAKING_OVER -> context.getString(R.string.island_taking_over_text)
        IslandType.MOVED_TO_REMOTE -> context.getString(R.string.island_moved_to_remote_text)
        IslandType.MOVED_TO_OTHER_DEVICE ->
            if (reversed) {
                context.getString(R.string.island_moved_to_other_device_reversed_text)
            } else {
                context.getString(R.string.island_moved_to_other_device_text, otherDeviceName)
            }
    }

    private fun post() {
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(SMALL_ICON)
            .setContentTitle(deviceName)
            .setContentText(statusText())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setDeleteIntent(actionIntent(ACTION_DISMISSED, 1))

        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context, 0, launch,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }

        if (showsBattery()) {
            // Also visible on Android versions without chips
            builder.setSubText(batteryText())
        } else {
            // Same as the overlay: an action button instead of the battery
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, SMALL_ICON),
                    "Take over", // consider moving this to strings.xml
                    actionIntent(ACTION_TAKE_OVER, 2)
                ).build()
            )
        }

        if (Build.VERSION.SDK_INT >= 36) {
            builder.setRequestPromotedOngoing(true)
            // No chip text = the chip shows only the small icon
            if (showsBattery()) builder.setShortCriticalText(batteryText())
        }

        notificationManager.notify(NOTIF_ID, builder.build())
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AirPods status",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun resetAutoCloseTimer() {
        autoCloseRunnable?.let { mainHandler.removeCallbacks(it) }
        autoCloseRunnable = Runnable { close() }
        mainHandler.postDelayed(autoCloseRunnable!!, AUTO_CLOSE_MS)
    }

    fun close() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { close() }
            return
        }
        if (isClosing) return
        isClosing = true

        try {
            autoCloseRunnable?.let { mainHandler.removeCallbacks(it) }

            if (receiverRegistered) {
                try {
                    context.unregisterReceiver(receiver)
                } catch (e: Exception) {
                    Log.e(TAG, "Error unregistering receiver: $e")
                }
                receiverRegistered = false
            }

            notificationManager.cancel(NOTIF_ID)
            ServiceManager.getService()?.islandOpen = false
            isVisible = false
        } finally {
            isClosing = false
        }
    }

    fun forceClose() = close()
}

