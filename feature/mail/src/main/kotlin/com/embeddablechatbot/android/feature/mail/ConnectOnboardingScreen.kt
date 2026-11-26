package com.embeddablechatbot.android.feature.mail

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.embeddablechatbot.android.core.designsystem.*

@Composable
fun ConnectOnboardingScreen(onFinished:()->Unit,viewModel:MailViewModel=hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context=LocalContext.current
    LifecycleResumeEffect(viewModel){viewModel.refresh();onPauseOrDispose{}}
    LaunchedEffect(viewModel){viewModel.effect.collect{effect->
        if(effect is MailEffect.OpenBrowser){
            val uri=Uri.parse(effect.url)
            try{CustomTabsIntent.Builder().setShareState(CustomTabsIntent.SHARE_STATE_OFF).build().launchUrl(context,uri)}
            catch(_:ActivityNotFoundException){context.startActivity(Intent(Intent.ACTION_VIEW,uri))}
        }
    }}
    LaunchedEffect(state.notice,state.busy){if(state.notice==MailNotice.Connected&&!state.busy)onFinished()}
    Scaffold {padding->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(28.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Spacer(Modifier.height(28.dp))
            Icon(Icons.Default.Email,null,Modifier.size(64.dp),tint=MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.connect_onboard_title),style=MaterialTheme.typography.headlineLarge,modifier=Modifier.semantics{heading()})
            Text(stringResource(R.string.connect_onboard_body),style=MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.connect_onboard_permissions),color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.connect_onboard_safety),color=MaterialTheme.colorScheme.onSurfaceVariant)
            state.error?.let{StatusBanner(it.message(),BannerTone.Error,actionLabel=stringResource(R.string.connect_onboard_retry),onAction=viewModel::startConnect)}
            state.declined?.let{StatusBanner(it,BannerTone.Warning)}
            if(state.notice==MailNotice.Unfinished)StatusBanner(stringResource(R.string.mail_unfinished_notice),BannerTone.Warning,actionLabel=stringResource(R.string.connect_onboard_retry),onAction={viewModel.startConnect()})
            if(state.loading&&state.status==null)CircularProgressIndicator()
            val available=state.status?.let{it.serverReady&&it.eligible}==true
            if(state.status!=null&&!available)StatusBanner(stringResource(R.string.connect_onboard_unavailable),BannerTone.Warning,actionLabel=stringResource(R.string.connect_onboard_retry),onAction=viewModel::refresh)
            Button(onClick=viewModel::startConnect,enabled=available&&!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Text(stringResource(if(state.busy)R.string.connect_onboard_opening else R.string.mail_connect))}
            OutlinedButton(onClick=onFinished,enabled=!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(stringResource(R.string.connect_onboard_later))}
            Text(stringResource(R.string.connect_onboard_later_body),style=MaterialTheme.typography.bodySmall)
        }
    }
}
