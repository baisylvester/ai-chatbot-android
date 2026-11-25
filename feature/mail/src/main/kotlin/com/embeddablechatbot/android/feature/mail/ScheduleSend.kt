package com.embeddablechatbot.android.feature.mail

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAdjusters

/** The server accepts sends from one minute to 60 days ahead. */
private val MIN_AHEAD: Duration = Duration.ofMinutes(2)
private val MAX_AHEAD: Duration = Duration.ofDays(60)

fun sendTimeAllowed(at: Instant, now: Instant = Instant.now()) = at.isAfter(now.plus(MIN_AHEAD)) && at.isBefore(now.plus(MAX_AHEAD))

/** Quick choices, in the device's time zone, that are always in the allowed window. */
fun schedulePresets(now: LocalDateTime, zone: ZoneId): List<Pair<String, Instant>> {
    val tomorrow = now.toLocalDate().plusDays(1)
    val monday = now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY))
    return listOf(
        "In 1 hour" to now.plusHours(1).atZone(zone).toInstant(),
        "Tomorrow morning" to tomorrow.atTime(8, 0).atZone(zone).toInstant(),
        "Tomorrow afternoon" to tomorrow.atTime(13, 0).atZone(zone).toInstant(),
        "Monday morning" to monday.atTime(8, 0).atZone(zone).toInstant(),
    )
}

fun formatSendTime(at: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(at.atZone(zone))

@Composable
fun ScheduleSendDialog(onDismiss: () -> Unit, onPick: (Instant) -> Unit) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val presets = remember { schedulePresets(LocalDateTime.now(zone), zone) }
    var invalid by remember { mutableStateOf(false) }
    fun pickCustom() {
        val today = LocalDate.now(zone)
        DatePickerDialog(context, { _, year, month, day ->
            val date = LocalDate.of(year, month + 1, day)
            TimePickerDialog(context, { _, hour, minute ->
                val at = date.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant()
                if (sendTimeAllowed(at)) onPick(at) else invalid = true
            }, 8, 0, DateFormat.is24HourFormat(context)).show()
        }, today.year, today.monthValue - 1, today.dayOfMonth).apply {
            datePicker.minDate = System.currentTimeMillis()
            datePicker.maxDate = System.currentTimeMillis() + MAX_AHEAD.toMillis() - 60_000
        }.show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.review_schedule)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                presets.forEach { (label, at) ->
                    TextButton(onClick = { onPick(at) }, modifier = Modifier.fillMaxWidth()) { Text("$label · ${formatSendTime(at, zone)}") }
                }
                TextButton(onClick = ::pickCustom, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.review_schedule_custom)) }
                if (invalid) Text(stringResource(R.string.review_schedule_invalid))
                Text(stringResource(R.string.review_schedule_note))
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.mail_cancel)) } },
    )
}
