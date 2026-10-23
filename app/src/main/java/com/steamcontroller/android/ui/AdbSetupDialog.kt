package com.steamcontroller.android.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.steamcontroller.android.R
import com.steamcontroller.android.adb.ShellLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One-time setup of the app's own ADB access (the replacement for Shizuku).
 *
 * Android 11+: enable Wireless debugging, tap "Pair device with pairing code", and type the
 * port and 6-digit code shown there. Older Android: nothing to pair here; the user runs
 * `adb tcpip 5555` once from a PC, and the first start asks to allow the connection.
 */
@Composable
fun AdbSetupDialog(onDismiss: () -> Unit, onPaired: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var port by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val needsPairing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.adb_setup_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (needsPairing) {
                    Text(stringResource(R.string.adb_setup_steps), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(R.string.adb_setup_port)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) },
                        label = { Text(stringResource(R.string.adb_setup_code)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                } else {
                    Text(stringResource(R.string.adb_setup_legacy), style = MaterialTheme.typography.bodyMedium)
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && (!needsPairing || (port.isNotEmpty() && code.length == 6)),
                onClick = {
                    busy = true
                    message = context.getString(R.string.adb_setup_working)
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                if (needsPairing) ShellLauncher.pair(context, port.toInt(), code)
                                // Proves the full path works, so "ready" is never a guess.
                                ShellLauncher.start(context)
                            }
                        }
                        busy = false
                        if (result.isSuccess) onPaired()
                        else message = context.getString(R.string.adb_setup_failed, result.exceptionOrNull()?.message ?: "?")
                    }
                },
            ) { Text(stringResource(if (needsPairing) R.string.adb_setup_pair else R.string.adb_setup_connect)) }
        },
        dismissButton = {
            Column {
                TextButton(onClick = {
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Throwable) {}
                }) { Text(stringResource(R.string.adb_setup_open_dev)) }
                TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            }
        },
    )
}
