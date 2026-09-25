package com.cedagova.fastreader.account.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.cedagova.fastreader.R
import com.cedagova.fastreader.ui.theme.Sizes
import com.cedagova.fastreader.ui.theme.Spacing
import com.cedagova.reader.account.AccountOutcome
import com.cedagova.reader.account.ReaderAccountActions
import com.cedagova.reader.account.ReaderAccountState

/**
 * The line above the forms: the operation in flight, or the last outcome. The
 * outcome is a live region, so a screen reader hears a rejection when it
 * appears rather than on the next swipe (REQ-060), and it can be dismissed.
 */
@Composable
internal fun ActivityAndOutcome(state: ReaderAccountState, actions: ReaderAccountActions) {
    val activity = state.activity
    val outcome = state.outcome
    if (activity == null && outcome == null) return
    Spacer(Modifier.height(Spacing.Medium))
    if (activity != null) {
        Text(
            text = stringResource(R.string.account_working),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().testTag("account_activity"),
        )
    }
    if (outcome != null) {
        val text = describe(outcome)
        val isFailure = outcome.isFailure()
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isFailure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("account_outcome"),
        )
        TextButton(
            onClick = actions::dismissOutcome,
            modifier = Modifier.defaultMinSize(minHeight = Sizes.TouchTarget).testTag("account_dismiss"),
        ) {
            Text(stringResource(R.string.account_dismiss))
        }
    }
    Spacer(Modifier.height(Spacing.XSmall))
    HorizontalDivider()
}

/**
 * One sentence per outcome, quoting what the library reported and never
 * paraphrasing a code: the provider's `invalid_credentials` is what a reader
 * types into a bug report, so it is shown verbatim.
 */
@Composable
internal fun describe(outcome: AccountOutcome): String = when (outcome) {
    AccountOutcome.CodeSent -> stringResource(R.string.account_outcome_code_sent)

    AccountOutcome.RecoveryCodeSent -> stringResource(R.string.account_outcome_recovery_sent)

    AccountOutcome.PasswordSet -> stringResource(R.string.account_outcome_password_set)

    AccountOutcome.SignedOutLocally -> stringResource(R.string.account_outcome_signed_out)

    AccountOutcome.OtherDevicesSignedOut -> stringResource(R.string.account_outcome_others_signed_out)

    is AccountOutcome.ProviderRejected -> stringResource(
        R.string.account_outcome_provider_rejected,
        details(http = outcome.status, code = outcome.code),
        outcome.description,
    )

    is AccountOutcome.TryLater -> stringResource(
        R.string.account_outcome_try_later,
        details(
            http = outcome.status,
            code = outcome.code,
            retryAfterSeconds = outcome.retryAfterSeconds,
            requestId = outcome.requestId,
        ),
    )

    AccountOutcome.NetworkUnavailable -> stringResource(R.string.account_outcome_network)

    is AccountOutcome.ConfigurationMismatch -> stringResource(R.string.account_outcome_mismatch, outcome.reason)

    is AccountOutcome.SignInUnavailable -> stringResource(R.string.account_outcome_sign_in_unavailable, outcome.reason)

    is AccountOutcome.SessionGone -> stringResource(
        R.string.account_outcome_session_gone,
        details(code = outcome.code, requestId = outcome.requestId),
    )

    is AccountOutcome.Forbidden -> stringResource(
        R.string.account_outcome_forbidden,
        details(code = outcome.code, requestId = outcome.requestId),
    )

    is AccountOutcome.ApiError -> stringResource(
        R.string.account_outcome_api_error,
        details(http = outcome.status, code = outcome.code, requestId = outcome.requestId),
        outcome.description,
    )

    AccountOutcome.StorageUnavailable -> stringResource(R.string.account_outcome_storage_unavailable)

    AccountOutcome.UnexpectedResponse -> stringResource(R.string.account_outcome_unexpected_response)
}

/** `HTTP 400 · invalid_credentials · request id …`: only the parts the library reported, in a fixed order. */
@Composable
private fun details(
    http: Int? = null,
    code: String? = null,
    retryAfterSeconds: Long? = null,
    requestId: String? = null,
): String = listOfNotNull(
    http?.let { stringResource(R.string.account_detail_http, it) },
    code,
    retryAfterSeconds?.let { stringResource(R.string.account_detail_retry_after, it) },
    requestId?.let { stringResource(R.string.account_detail_request_id, it) },
).joinToString(" · ").ifEmpty { "—" }

private fun AccountOutcome.isFailure(): Boolean = when (this) {
    AccountOutcome.CodeSent,
    AccountOutcome.RecoveryCodeSent,
    AccountOutcome.PasswordSet,
    AccountOutcome.SignedOutLocally,
    AccountOutcome.OtherDevicesSignedOut,
    -> false

    else -> true
}
