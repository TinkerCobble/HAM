package com.tinkercobble.ham

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import com.tinkercobble.ham.data.EventEntryTime
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal object EventEntryPreferences {
    private const val NAME = "ham-event-entry"
    fun defaultMinutes(context: Context): Int =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getInt("default_minutes", 60)
            .takeIf { it in listOf(15, 30, 60, 120) } ?: 60
    fun setDefaultMinutes(context: Context, minutes: Int) {
        require(minutes in listOf(15, 30, 60, 120))
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putInt("default_minutes", minutes).apply()
    }
}

internal fun readableDate(date: LocalDate): String =
    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))

@Composable
internal fun EventTimeField(value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clock = EventEntryTime.parse(value)
    val formatted = clock?.format(DateTimeFormatter.ofPattern(
        if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"))
    var focused by remember { mutableStateOf(false) }
    Row(modifier) {
        OutlinedTextField(if (focused) value else formatted ?: value, onValueChange, label = { Text(label) },
            supportingText = { if (value.isNotBlank() && clock == null) Text("Enter a time, such as 3:30 PM") },
            isError = value.isNotBlank() && clock == null,
            singleLine = true, modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused })
        OutlinedButton(onClick = {
            val current = clock ?: LocalTime.of(9, 0)
            TimePickerDialog(context, { _, hour, minute ->
                onValueChange(LocalTime.of(hour, minute).toString())
            }, current.hour, current.minute, DateFormat.is24HourFormat(context)).show()
        }) { Text("Clock") }
    }
}

@Composable
internal fun EventDateField(value: String, onValueChange: (String) -> Unit) {
    val context = LocalContext.current
    val date = runCatching { LocalDate.parse(value) }.getOrNull()
    Row(Modifier.fillMaxWidth()) {
        OutlinedTextField(date?.let(::readableDate) ?: value, { onValueChange(it) },
            label = { Text("Date") }, readOnly = true, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = {
            val current = date ?: LocalDate.now()
            DatePickerDialog(context, { _, year, month, day ->
                onValueChange(LocalDate.of(year, month + 1, day).toString())
            }, current.year, current.monthValue - 1, current.dayOfMonth).show()
        }) { Text("Change") }
    }
}
