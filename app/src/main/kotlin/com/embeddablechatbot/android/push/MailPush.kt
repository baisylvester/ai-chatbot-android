package com.embeddablechatbot.android.push

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.embeddablechatbot.android.BuildConfig
import com.embeddablechatbot.android.MainActivity
import com.embeddablechatbot.android.R
import com.embeddablechatbot.android.core.domain.chat.ApplicationScope
import com.embeddablechatbot.android.core.domain.model.AuthState
import com.embeddablechatbot.android.core.domain.repository.AuthRepository
import com.embeddablechatbot.android.core.domain.repository.MailRepository
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * New-mail push through Firebase Cloud Messaging. Off unless the build has a Firebase project
 * (firebase.appId, firebase.apiKey, firebase.projectId, firebase.senderId in local.properties or the
 * environment) and the server has FCM_SERVICE_ACCOUNT_JSON. The token is registered for the signed-in
 * account and deleted on sign-out, which also stops delivery to that account.
 */
@Singleton
class MailPush @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val auth: AuthRepository,
    private val mail: MailRepository,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    val configured: Boolean get() = BuildConfig.FIREBASE_APP_ID.isNotBlank() && BuildConfig.FIREBASE_API_KEY.isNotBlank() &&
        BuildConfig.FIREBASE_PROJECT_ID.isNotBlank() && BuildConfig.FIREBASE_SENDER_ID.isNotBlank()

    private fun app(): FirebaseApp? {
        if (!configured) return null
        return FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder().setApplicationId(BuildConfig.FIREBASE_APP_ID).setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID).setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID).build(),
        )
    }

    /** Follows the signed-in account: registers this phone on sign-in, forgets the token on sign-out. */
    suspend fun run() {
        if (app() == null) return
        var signedIn = false
        auth.authState.map { (it as? AuthState.SignedIn)?.account?.userId }.distinctUntilChanged().collect { user ->
            if (user != null) { signedIn = true; token()?.let { mail.registerPush(it) } }
            else if (signedIn) { signedIn = false; deleteToken() }
        }
    }

    fun onNewToken(token: String) {
        if (auth.authState.value is AuthState.SignedIn) scope.launch { mail.registerPush(token) }
    }

    private suspend fun token(): String? = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task -> cont.resume(if (task.isSuccessful) task.result else null) }
    }

    private suspend fun deleteToken() = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener { cont.resume(Unit) }
    }

    fun show(title: String, body: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_INBOX).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_MAIL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(title.take(120)).setContentText(body.take(300))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(500))).setContentIntent(open).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        context.getSystemService(NotificationManager::class.java).notify(NEW_MAIL_ID, notification)
    }

    companion object {
        const val CHANNEL_MAIL = "mail_updates"
        private const val NEW_MAIL_ID = 4100
    }
}

@AndroidEntryPoint
class MailPushService : FirebaseMessagingService() {
    @Inject lateinit var push: MailPush

    override fun onNewToken(token: String) = push.onNewToken(token)

    /** Foreground messages arrive here; in the background the system shows the notification itself. */
    override fun onMessageReceived(message: RemoteMessage) {
        val notification = message.notification ?: return
        push.show(notification.title ?: getString(R.string.app_name), notification.body.orEmpty())
    }
}
