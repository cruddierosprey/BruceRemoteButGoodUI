package io.bruceremote.app.firmware

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import io.bruceremote.app.FirmwareActivity
import io.bruceremote.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Checks for new firmware releases on GitHub and notifies the user.
 *
 * Call [checkOnStartup] from MainActivity.onCreate when the app first launches.
 */
class FirmwareUpdateChecker(private val context: Context) {

    private val client = GitHubReleaseClient(context)
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
        as NotificationManager

    init {
        createNotificationChannel()
    }

    /**
     * Check for updates if enough time has passed since the last check.
     * This is safe to call from the main thread — it launches a coroutine.
     */
    fun checkOnStartup(scope: CoroutineScope) {
        val now = System.currentTimeMillis()
        val lastCheck = client.lastCheckTimeMs
        if (now - lastCheck < GitHubReleaseClient.CHECK_INTERVAL_MS) {
            return // Too soon since last check
        }

        scope.launch(Dispatchers.IO) {
            val release = client.checkForUpdate()
            client.lastCheckTimeMs = System.currentTimeMillis()

            if (release != null) {
                withContext(Dispatchers.Main) {
                    showUpdateNotification(release)
                }
                client.lastNotifiedTag = release.tagName
            }
        }
    }

    /**
     * Force an update check regardless of interval. Use when user taps
     * "Check for updates" manually.
     */
    fun checkNow(scope: CoroutineScope, onResult: (UpdateResult) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val release = client.checkForUpdate()
            client.lastCheckTimeMs = System.currentTimeMillis()

            withContext(Dispatchers.Main) {
                if (release != null) {
                    client.lastNotifiedTag = release.tagName
                    onResult(UpdateResult.NewRelease(release))
                } else {
                    onResult(UpdateResult.NoUpdate)
                }
            }
        }
    }

    private fun showUpdateNotification(release: GitHubReleaseClient.Release) {
        val intent = Intent(context, FirmwareActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_RELEASE_TAG, release.tagName)
            putExtra(EXTRA_RELEASE_URL, release.htmlUrl)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE_UPDATE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(context.getString(R.string.update_notification_title))
            .setContentText(
                context.getString(
                    R.string.update_notification_text,
                    release.tagName,
                ),
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    context.getString(
                        R.string.update_notification_big_text,
                        release.tagName,
                        release.name,
                    ),
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID_UPDATE, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.update_channel_description)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    sealed class UpdateResult {
        data class NewRelease(val release: GitHubReleaseClient.Release) : UpdateResult()
        data object NoUpdate : UpdateResult()
    }

    companion object {
        private const val CHANNEL_ID = "firmware_updates"
        private const val NOTIFICATION_ID_UPDATE = 1001
        private const val REQUEST_CODE_UPDATE = 1001
        const val EXTRA_RELEASE_TAG = "release_tag"
        const val EXTRA_RELEASE_URL = "release_url"
    }
}
