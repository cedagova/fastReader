package com.cedagova.reader.auth.host

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cedagova.reader.auth.ReaderAuthClient
import com.cedagova.reader.auth.ReaderAuthException
import com.cedagova.reader.auth.ReaderSessionState
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The host's single screen: the minimal sign-in surface the contract needs
 * exercised on a device (CONTRACT.md) and nothing more — email-code sign-up
 * and sign-in, password sign-in, code-based recovery, and a signed-in view
 * with the user id, the capabilities call, and the two sign-out actions.
 * Every outcome is also written to logcat under `ReaderAuthHost` (never a
 * token, never a code).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as HostApplication
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HostScreen(packageName = packageName, client = app.auth)
                }
            }
        }
    }
}

@Composable
private fun HostScreen(packageName: String, client: ReaderAuthClient?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.host_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.host_package, packageName), style = MaterialTheme.typography.bodySmall)
        if (client == null) {
            Text(stringResource(R.string.host_not_configured), style = MaterialTheme.typography.bodyLarge)
        } else {
            AuthScreen(client)
        }
    }
}

@Composable
private fun AuthScreen(client: ReaderAuthClient) {
    val state by client.sessionState.collectAsStateWithLifecycle(initialValue = client.currentState())
    var status by rememberSaveable { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val idle = stringResource(R.string.host_status_idle)
    val workingText = stringResource(R.string.host_status_working)

    /** Runs one module operation, then shows and logs its outcome. */
    fun run(name: String, block: suspend () -> String) {
        if (working) return
        working = true
        status = workingText
        scope.launch {
            status = try {
                block().also { Log.i(TAG, "$name: $it") }
            } catch (e: ReaderAuthException) {
                Log.i(TAG, "$name failed: ${e.javaClass.simpleName} ${e.message}")
                "${e.javaClass.simpleName}: ${e.message}"
            } finally {
                working = false
            }
        }
    }

    when (val current = state) {
        ReaderSessionState.Initializing -> Text(stringResource(R.string.host_loading))
        ReaderSessionState.SignedOut -> SignedOutView(client, ::run)
        is ReaderSessionState.SignedIn -> SignedInView(client, current, ::run)
    }
    HorizontalDivider()
    Text(status.ifBlank { idle }, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun SignedOutView(client: ReaderAuthClient, run: (String, suspend () -> String) -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var newAccount by rememberSaveable { mutableStateOf(false) }
    val codeSent = stringResource(R.string.host_status_code_sent)
    val recoverySent = stringResource(R.string.host_status_recovery_sent)
    val signedIn = stringResource(R.string.host_signed_in)

    Text(stringResource(R.string.host_signed_out), style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = email,
        onValueChange = { email = it.trim() },
        label = { Text(stringResource(R.string.host_field_email)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )

    Text(stringResource(R.string.host_section_code), style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(checked = newAccount, onCheckedChange = { newAccount = it })
        Text(stringResource(R.string.host_new_account))
    }
    Button(onClick = { run("requestEmailCode") { client.requestEmailCode(email, createUser = newAccount); codeSent } }) {
        Text(stringResource(R.string.host_send_code))
    }
    OutlinedTextField(
        value = code,
        onValueChange = { code = it.filter(Char::isDigit).take(6) },
        label = { Text(stringResource(R.string.host_field_code)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { run("verifyEmailCode") { "$signedIn ${client.verifyEmailCode(email, code).userId}" } }) {
        Text(stringResource(R.string.host_verify_code))
    }

    Text(stringResource(R.string.host_section_password), style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(stringResource(R.string.host_field_password)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { run("signInWithPassword") { "$signedIn ${client.signInWithPassword(email, password).userId}" } }) {
        Text(stringResource(R.string.host_sign_in_password))
    }

    Text(stringResource(R.string.host_section_recovery), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { run("requestRecoveryCode") { client.requestRecoveryCode(email); recoverySent } }) {
            Text(stringResource(R.string.host_send_recovery))
        }
        OutlinedButton(onClick = { run("verifyRecoveryCode") { "$signedIn ${client.verifyRecoveryCode(email, code).userId}" } }) {
            Text(stringResource(R.string.host_verify_recovery))
        }
    }
}

@Composable
private fun SignedInView(client: ReaderAuthClient, session: ReaderSessionState.SignedIn, run: (String, suspend () -> String) -> Unit) {
    var capabilities by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    val capabilitiesText = stringResource(R.string.host_status_capabilities)
    val signedOut = stringResource(R.string.host_status_signed_out)
    val othersSignedOut = stringResource(R.string.host_status_others_signed_out)
    val passwordSet = stringResource(R.string.host_status_password_set)

    Text(stringResource(R.string.host_signed_in), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.host_user_id, session.userId))
    Text(stringResource(R.string.host_email, session.email ?: "—"))
    Text(stringResource(R.string.host_expires, session.expiresAt.toString()), style = MaterialTheme.typography.bodySmall)

    Button(onClick = {
        run("capabilities") {
            val document = client.capabilities()
            capabilities = pretty.encodeToString(JsonObject.serializer(), document)
            capabilitiesText.format("200: ${document["schemaVersion"]}")
        }
    }) {
        Text(stringResource(R.string.host_capabilities))
    }
    if (capabilities.isNotBlank()) {
        Text(capabilities, style = MaterialTheme.typography.bodySmall)
    }

    OutlinedTextField(
        value = newPassword,
        onValueChange = { newPassword = it },
        label = { Text(stringResource(R.string.host_field_new_password)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(onClick = { run("setPassword") { client.setPassword(newPassword); newPassword = ""; passwordSet } }) {
        Text(stringResource(R.string.host_set_password))
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { run("signOut") { client.signOut(); signedOut } }) {
            Text(stringResource(R.string.host_sign_out))
        }
        OutlinedButton(onClick = { run("signOutOtherDevices") { client.signOutOtherDevices(); othersSignedOut } }) {
            Text(stringResource(R.string.host_sign_out_others))
        }
    }
}

private val pretty = Json { prettyPrint = true }
