package com.embeddablechatbot.android.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.embeddablechatbot.android.core.domain.model.AppError

/** User-facing text for an error. Server-provided messages are already written for end users. */
@Composable
fun AppError.message(): String = when (this) {
    AppError.Unauthorized -> stringResource(R.string.ds_error_unauthorized)
    is AppError.Forbidden -> message
    is AppError.NotFound -> message
    is AppError.Conflict -> message
    is AppError.Gone -> message
    is AppError.RateLimited -> retryAfterSeconds?.let { pluralStringResource(R.plurals.ds_error_rate_limited, it.toInt(), it.toInt()) }
        ?: stringResource(R.string.ds_error_rate_limited_generic)
    is AppError.Invalid -> message
    is AppError.Unavailable -> message
    is AppError.Server -> stringResource(R.string.ds_error_server)
    is AppError.Network -> if (uncertain) stringResource(R.string.ds_error_network_uncertain) else stringResource(R.string.ds_error_offline)
    is AppError.Protocol -> stringResource(R.string.ds_error_protocol)
    is AppError.NotConfigured -> message.ifBlank { stringResource(R.string.ds_error_not_configured) }
}
