package dev.hermeskotlin.ui.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.hermeskotlin.core.voice.MicrophoneCheck

@Composable
actual fun rememberMicrophonePermission(): MicrophonePermission {
    val context = LocalContext.current
    // The callbacks of the request in flight; one dialog shows at a time.
    val pending = remember { arrayOfNulls<Pair<() -> Unit, () -> Unit>>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val (onDenied, onGranted) = pending[0] ?: return@rememberLauncherForActivityResult
        pending[0] = null
        if (granted) onGranted() else onDenied()
    }
    return remember(launcher) {
        MicrophonePermission { onDenied, onGranted ->
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                onGranted()
            } else {
                pending[0] = onDenied to onGranted
                launcher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}

@Composable
actual fun rememberMicrophoneSettings(): () -> Unit {
    val context = LocalContext.current
    return remember(context) {
        {
            // This app's own page: a second refusal there is where the permission is given for good (Android
            // stops showing the dialog after two, so the page is the only way back).
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

@Composable
actual fun rememberMicrophoneCheck(): MicrophoneCheck {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        MicrophoneCheck {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }
    }
}
