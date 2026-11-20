package com.embeddablechatbot.android.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.embeddablechatbot.android.MailAssistantApp
import com.embeddablechatbot.android.MainActivity
import com.embeddablechatbot.android.R
import com.embeddablechatbot.android.core.domain.model.ActionFilter
import com.embeddablechatbot.android.core.domain.model.AppError
import com.embeddablechatbot.android.core.domain.model.AuthState
import com.embeddablechatbot.android.core.domain.model.Outcome
import com.embeddablechatbot.android.core.domain.repository.AuthRepository
import com.embeddablechatbot.android.core.domain.repository.MailRepository
import com.embeddablechatbot.android.core.domain.repository.PreferencesRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Best-effort reminder that approvals are waiting. WorkManager decides when it runs (roughly every
 * 30 minutes with a network), so this is not real-time push. It only reads; it never approves.
 */
@HiltWorker
class ApprovalReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val auth: AuthRepository,
    private val mail: MailRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        (applicationContext as MailAssistantApp).startup.awaitReady()
        val account = (auth.authState.value as? AuthState.SignedIn)?.account ?: return Result.success()
        return when (val result = mail.actions(ActionFilter.Pending)) {
            is Outcome.Ok -> {
                val ids = result.value.map { it.id }.toSet()
                val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val key = "notified_${account.userId}"
                val seen = prefs.getStringSet(key, emptySet()).orEmpty()
                if ((ids - seen).isNotEmpty()) notifyPending()
                prefs.edit().putStringSet(key, ids).apply()
                Result.success()
            }
            is Outcome.Err -> if (result.error is AppError.Network || result.error is AppError.Server) Result.retry() else Result.success()
        }
    }

    private fun notifyPending() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        // Tapping only opens the mailbox screen; approval always requires reviewing in the app.
        val open = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_MAIL)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = applicationContext.getString(R.string.notification_approvals_title)
        val body = applicationContext.getString(R.string.notification_approvals_body)
        val publicVersion = NotificationCompat.Builder(applicationContext, MailAssistantApp.CHANNEL_APPROVALS)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).build()
        val notification = NotificationCompat.Builder(applicationContext, MailAssistantApp.CHANNEL_APPROVALS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    companion object {
        const val NAME = "approval-reminders"
        private const val PREFS = "approval_reminders"
        private const val NOTIFICATION_ID = 1001
    }
}

/** Schedules or cancels the reminder worker from the user's preference and sign-in state. */
@Singleton
class ReminderScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val auth: AuthRepository,
    private val preferences: PreferencesRepository,
) {
    suspend fun run() {
        combine(auth.authState, preferences.preferences) { a, p -> a is AuthState.SignedIn && p.approvalReminders }
            .distinctUntilChanged()
            .collect { enabled ->
                val work = WorkManager.getInstance(context)
                if (enabled) {
                    val request = PeriodicWorkRequestBuilder<ApprovalReminderWorker>(30, TimeUnit.MINUTES)
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .build()
                    work.enqueueUniquePeriodicWork(ApprovalReminderWorker.NAME, ExistingPeriodicWorkPolicy.KEEP, request)
                } else {
                    work.cancelUniqueWork(ApprovalReminderWorker.NAME)
                    context.getSharedPreferences("approval_reminders", Context.MODE_PRIVATE).edit().clear().apply()
                }
            }
    }
}
