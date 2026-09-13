package com.cedagova.reader.auth.host

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cedagova.reader.auth.ReaderAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "ReaderAuthHost"

/**
 * The host's single screen: which package this is, whether the library's
 * `INTERNET` permission arrived through the manifest merge, and the status of
 * one HTTPS probe. That is everything #92 needs to observe on a device; #93
 * replaces the probe with the real sign-in flow.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val internetGranted =
            checkSelfPermission(ReaderAuth.REQUIRED_PERMISSION) == PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "package=$packageName internetGranted=$internetGranted")
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ProbeScreen(packageName = packageName, internetGranted = internetGranted)
                }
            }
        }
    }
}

@Composable
private fun ProbeScreen(packageName: String, internetGranted: Boolean) {
    val url = HttpsProbe.DEFAULT_URL
    // The attempt counter is what "Probe again" bumps; the result survives a
    // rotation so a recreated activity does not silently probe a second time.
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    var result by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(attempt) {
        if (result != null) return@LaunchedEffect
        val outcome = withContext(Dispatchers.IO) { HttpsProbe.get(url) }
        val line = outcome.describe(url)
        Log.i(TAG, "probe attempt=$attempt $line")
        result = line
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.probe_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.probe_package, packageName))
        Text(
            stringResource(
                if (internetGranted) R.string.probe_permission_granted
                else R.string.probe_permission_missing,
            ),
        )
        Text(stringResource(R.string.probe_target, url.toString()))
        Text(
            text = result ?: stringResource(R.string.probe_running),
            style = MaterialTheme.typography.titleLarge,
        )
        Button(
            onClick = {
                result = null
                attempt += 1
            },
            enabled = result != null,
        ) {
            Text(stringResource(R.string.probe_again))
        }
    }
}
