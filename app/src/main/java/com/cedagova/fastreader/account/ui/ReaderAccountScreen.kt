package com.cedagova.fastreader.account.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.cedagova.fastreader.R
import com.cedagova.fastreader.ui.components.BackButton
import com.cedagova.fastreader.ui.components.CheckboxRow
import com.cedagova.fastreader.ui.components.SectionHeading
import com.cedagova.fastreader.ui.theme.Sizes
import com.cedagova.fastreader.ui.theme.Spacing
import com.cedagova.reader.account.LoadedCapabilities
import com.cedagova.reader.account.ReaderAccountActions
import com.cedagova.reader.account.ReaderAccountState

/**
 * The Reader account surface (#100): one screen over Settings, rendered from
 * a [ReaderAccountState] and reporting every tap to [ReaderAccountActions].
 *
 * Stateless like the settings screen — every state it can show is a value —
 * which is what lets the `account_*` goldens and the accessibility sweep run
 * with no SDK behind them. The only state it keeps is what a reader typed:
 * the address and the "new account" choice survive rotation; the code and
 * the passwords deliberately do not, so a secret is never written to the
 * saved-instance bundle.
 *
 * ## The three states, as the definition names them
 *
 * - **Not configured** names the missing values and offers nothing.
 * - **Signed out** is the contract's sign-in methods in its preference order:
 *   the emailed code (with the sign-up choice), the password, and code-based
 *   recovery — each button exactly one library operation.
 * - **Signed in as `<email>`** shows the capabilities document as returned
 *   with the request id of the call, lets a recovery finish with a new
 *   password, and offers the two sign-outs.
 *
 * An operation in flight disables every control and says so; its outcome is
 * one line under the forms, announced when it appears (REQ-060), quoting the
 * provider's or the backend's code and the request id as the library
 * reported them. Nothing here retries, re-sends, or interprets a code.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderAccountScreen(
    state: ReaderAccountState,
    actions: ReaderAccountActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize().testTag("account_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.account_title)) },
                navigationIcon = {
                    BackButton(stringResource(R.string.account_back), onBack, tag = "account_back")
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Large),
        ) {
            // First, above the forms: on the reference phone the signed-out
            // forms run past one viewport, and an outcome under them was a
            // rejection a reader had to scroll to find (seen on the emulator).
            ActivityAndOutcome(state, actions)
            when (state) {
                // Blank rather than a spinner, for the same reason the launch
                // routing is blank: the stored session is one small file read,
                // and "signed out" for a frame would be a false statement.
                ReaderAccountState.Loading -> Unit

                is ReaderAccountState.NotConfigured -> NotConfigured(state)

                is ReaderAccountState.SignedOut -> SignedOut(state, actions)

                is ReaderAccountState.SignedIn -> SignedIn(state, actions)
            }
            Spacer(Modifier.height(Spacing.XXLarge))
        }
    }
}

@Composable
private fun NotConfigured(state: ReaderAccountState.NotConfigured) {
    Heading(stringResource(R.string.account_not_configured_title), tag = "account_state")
    Text(
        text = stringResource(R.string.account_not_configured_body, state.missingValues.joinToString(", ")),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag("account_not_configured"),
    )
}

@Composable
private fun SignedOut(state: ReaderAccountState.SignedOut, actions: ReaderAccountActions) {
    val idle = state.activity == null
    var email by rememberSaveable { mutableStateOf("") }
    var newAccount by rememberSaveable { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val hasEmail = email.isNotBlank()
    val hasCode = code.length == CODE_LENGTH

    Heading(stringResource(R.string.account_signed_out_title), tag = "account_state")
    Summary(stringResource(R.string.account_signed_out_intro))
    Spacer(Modifier.height(Spacing.Small))
    OutlinedTextField(
        value = email,
        onValueChange = { email = it.trim() },
        label = { Text(stringResource(R.string.account_field_email)) },
        singleLine = true,
        enabled = idle,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth().testTag("account_email"),
    )

    SectionHeading(stringResource(R.string.account_section_code))
    Summary(stringResource(R.string.account_section_code_summary))
    CheckboxRow(
        label = stringResource(R.string.account_new_account),
        checked = newAccount,
        enabled = idle,
        onCheckedChange = { newAccount = it },
        tag = "account_new_account",
    )
    PrimaryAction(
        label = stringResource(R.string.account_send_code),
        enabled = idle && hasEmail,
        onClick = { actions.requestEmailCode(email, newAccount) },
        tag = "account_send_code",
    )
    Spacer(Modifier.height(Spacing.Small))
    OutlinedTextField(
        value = code,
        onValueChange = { code = it.filter(Char::isDigit).take(CODE_LENGTH) },
        label = { Text(stringResource(R.string.account_field_code)) },
        singleLine = true,
        enabled = idle,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag("account_code"),
    )
    PrimaryAction(
        label = stringResource(R.string.account_verify_code),
        enabled = idle && hasEmail && hasCode,
        onClick = { actions.verifyEmailCode(email, code) },
        tag = "account_verify_code",
    )

    SectionHeading(stringResource(R.string.account_section_password))
    PasswordField(
        value = password,
        onValueChange = { password = it },
        label = stringResource(R.string.account_field_password),
        enabled = idle,
        tag = "account_password",
    )
    PrimaryAction(
        label = stringResource(R.string.account_sign_in_password),
        enabled = idle && hasEmail && password.isNotEmpty(),
        onClick = { actions.signInWithPassword(email, password) },
        tag = "account_sign_in_password",
    )

    SectionHeading(stringResource(R.string.account_section_recovery))
    Summary(stringResource(R.string.account_section_recovery_summary))
    SecondaryAction(
        label = stringResource(R.string.account_send_recovery),
        enabled = idle && hasEmail,
        onClick = { actions.requestRecoveryCode(email) },
        tag = "account_send_recovery",
    )
    SecondaryAction(
        label = stringResource(R.string.account_verify_recovery),
        enabled = idle && hasEmail && hasCode,
        onClick = { actions.verifyRecoveryCode(email, code) },
        tag = "account_verify_recovery",
    )
}

@Composable
private fun SignedIn(state: ReaderAccountState.SignedIn, actions: ReaderAccountActions) {
    val idle = state.activity == null
    var newPassword by remember { mutableStateOf("") }

    Heading(stringResource(R.string.account_signed_in_title, state.email ?: state.userId), tag = "account_state")
    Text(
        text = stringResource(R.string.account_user_id, state.userId),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("account_user_id"),
    )

    Spacer(Modifier.height(Spacing.Large))
    Summary(stringResource(R.string.account_capabilities_summary))
    PrimaryAction(
        label = stringResource(
            if (state.capabilities ==
                null
            ) {
                R.string.account_capabilities_show
            } else {
                R.string.account_capabilities_refresh
            },
        ),
        enabled = idle,
        onClick = actions::loadCapabilities,
        tag = "account_capabilities",
    )
    state.capabilities?.let { Capabilities(it) }

    SectionHeading(stringResource(R.string.account_field_new_password))
    Summary(stringResource(R.string.account_set_password_summary))
    PasswordField(
        value = newPassword,
        onValueChange = { newPassword = it },
        label = stringResource(R.string.account_field_new_password),
        enabled = idle,
        tag = "account_new_password",
    )
    SecondaryAction(
        label = stringResource(R.string.account_set_password),
        enabled = idle && newPassword.isNotEmpty(),
        onClick = {
            actions.setPassword(newPassword)
            newPassword = ""
        },
        tag = "account_set_password",
    )

    SectionHeading(stringResource(R.string.account_sign_out))
    Summary(stringResource(R.string.account_sign_out_summary))
    PrimaryAction(
        label = stringResource(R.string.account_sign_out),
        enabled = idle,
        onClick = actions::signOut,
        tag = "account_sign_out",
    )
    Spacer(Modifier.height(Spacing.Small))
    Summary(stringResource(R.string.account_sign_out_others_summary))
    SecondaryAction(
        label = stringResource(R.string.account_sign_out_others),
        enabled = idle,
        onClick = actions::signOutOtherDevices,
        tag = "account_sign_out_others",
    )
}

/**
 * REQ-407: the document as the Reader API returned it, whole, and the request
 * id of the call — the id reader-api echoed, so a server log can be read
 * against this screen. Monospace so the JSON's own structure is the layout.
 */
@Composable
private fun Capabilities(capabilities: LoadedCapabilities) {
    Spacer(Modifier.height(Spacing.Small))
    Text(
        text = stringResource(R.string.account_capabilities_request_id, capabilities.requestId),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag("account_capabilities_request_id"),
    )
    Spacer(Modifier.height(Spacing.XSmall))
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = capabilities.document,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(Spacing.Medium).testTag("account_capabilities_document"),
        )
    }
}

// --- Building blocks ---------------------------------------------------------

@Composable
private fun Heading(text: String, tag: String) {
    Spacer(Modifier.height(Spacing.Large))
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().semantics { heading() }.testTag(tag),
    )
    Spacer(Modifier.height(Spacing.XSmall))
}

@Composable
private fun Summary(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(Spacing.XSmall))
}

@Composable
private fun PrimaryAction(label: String, enabled: Boolean, onClick: () -> Unit, tag: String) {
    Spacer(Modifier.height(Spacing.Small))
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = Sizes.TouchTarget).testTag(tag),
    ) {
        Text(label)
    }
}

@Composable
private fun SecondaryAction(label: String, enabled: Boolean, onClick: () -> Unit, tag: String) {
    Spacer(Modifier.height(Spacing.Small))
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = Sizes.TouchTarget).testTag(tag),
    ) {
        Text(label)
    }
}

/**
 * A masked field with a reveal, as a labelled text button rather than an
 * icon: the icon set this app ships has no eye, and "Show" says what it does
 * to a screen reader without a description of its own.
 */
@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    tag: String,
) {
    var revealed by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            enabled = enabled,
            visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.weight(1f).testTag(tag),
        )
        Spacer(Modifier.width(Spacing.Small))
        TextButton(
            onClick = { revealed = !revealed },
            modifier = Modifier.defaultMinSize(minHeight = Sizes.TouchTarget).testTag("${tag}_reveal"),
        ) {
            Text(stringResource(if (revealed) R.string.account_password_hide else R.string.account_password_show))
        }
    }
}

/** The provider emails six digits; anything else typed is not a code. */
private const val CODE_LENGTH = 6
