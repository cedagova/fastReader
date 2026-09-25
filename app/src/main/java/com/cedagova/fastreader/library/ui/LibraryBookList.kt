package com.cedagova.fastreader.library.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cedagova.fastreader.R
import com.cedagova.fastreader.library.BookStatus
import com.cedagova.fastreader.ui.theme.Sizes
import com.cedagova.fastreader.ui.theme.Spacing

@Composable
internal fun BookList(
    books: List<LibraryBookItem>,
    onRemove: (LibraryBookItem) -> Unit,
    onRemoveFromAccount: (LibraryBookItem) -> Unit,
    onRemoveAccountCopy: (LibraryBookItem) -> Unit,
    onAddToAccount: (LibraryBookItem) -> Unit,
    onCancelAddToAccount: (LibraryBookItem) -> Unit,
    onDismissAddToAccount: (LibraryBookItem) -> Unit,
    onDownloadAccountBook: (LibraryBookItem) -> Unit,
    onCancelDownload: (LibraryBookItem) -> Unit,
    onDismissDownload: (LibraryBookItem) -> Unit,
    onGrantAccess: (LibraryBookItem) -> Unit,
    onOpen: (LibraryBookItem) -> Unit,
    coverLoader: CoverLoader,
    wide: Boolean = false,
) {
    if (!wide) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("library_list"),
            contentPadding = PaddingValues(bottom = Spacing.XXLarge),
        ) {
            items(items = books, key = { it.id }) { book ->
                BookRow(
                    book = book,
                    onRemove = { onRemove(book) },
                    onRemoveFromAccount = { onRemoveFromAccount(book) },
                    onRemoveAccountCopy = { onRemoveAccountCopy(book) },
                    onAddToAccount = { onAddToAccount(book) },
                    onCancelAddToAccount = { onCancelAddToAccount(book) },
                    onDismissAddToAccount = { onDismissAddToAccount(book) },
                    onDownloadAccountBook = { onDownloadAccountBook(book) },
                    onCancelDownload = { onCancelDownload(book) },
                    onDismissDownload = { onDismissDownload(book) },
                    onGrantAccess = { onGrantAccess(book) },
                    onOpen = { onOpen(book) },
                    coverLoader = coverLoader,
                )
                HorizontalDivider()
            }
        }
        return
    }
    LazyVerticalGrid(
        // Adaptive, not a column count: the same rule then covers a 600 dp tablet,
        // a phone on its side and a 10" tablet, and it is the rule that keeps the
        // largest font size legible instead of a second breakpoint that would have
        // to be kept in step with it.
        columns = GridCells.Adaptive(minSize = bookColumnMinWidth()),
        modifier = Modifier.fillMaxSize().testTag("library_list"),
        contentPadding = PaddingValues(bottom = Spacing.XXLarge),
    ) {
        gridItems(items = books, key = { it.id }) { book ->
            // No `fillMaxHeight`/`weight` here, however much the dividers would
            // like to line up across a row: a lazy grid measures its items with an
            // unbounded height, where `fillMaxHeight` does nothing and a weight
            // resolves against infinity — which is how the first draft of this
            // rendered four books at zero height and an empty screen.
            Column {
                // The divider goes *above* the row here, where the list puts it
                // below. Every cell in a grid row starts at the same y and they
                // end at different ones, so a leading rule is the only one that
                // draws as an unbroken line between rows instead of a staircase.
                HorizontalDivider()
                BookRow(
                    book = book,
                    onRemove = { onRemove(book) },
                    onRemoveFromAccount = { onRemoveFromAccount(book) },
                    onRemoveAccountCopy = { onRemoveAccountCopy(book) },
                    onAddToAccount = { onAddToAccount(book) },
                    onCancelAddToAccount = { onCancelAddToAccount(book) },
                    onDismissAddToAccount = { onDismissAddToAccount(book) },
                    onDownloadAccountBook = { onDownloadAccountBook(book) },
                    onCancelDownload = { onCancelDownload(book) },
                    onDismissDownload = { onDismissDownload(book) },
                    onGrantAccess = { onGrantAccess(book) },
                    onOpen = { onOpen(book) },
                    coverLoader = coverLoader,
                )
            }
        }
    }
}

/**
 * The narrowest a book column may be, at the current font scale.
 *
 * A book row spends [BookRowFixedWidth] on things that do not grow with the font —
 * the 48 dp cover, the 48 dp remove button, the gaps between them and the row's own
 * padding — and everything else on the title, author and status line, which do.
 * So the minimum is a fixed part plus a scaled one, and the grid drops to fewer,
 * wider columns exactly when the text would otherwise start losing its ends
 * (REQ-205, REQ-301).
 */
@Composable
private fun bookColumnMinWidth(): Dp = BookRowFixedWidth + BookRowTextWidth * LocalDensity.current.fontScale

/** [CoverWidth] cover + 16 dp gap + 8 dp gap + 48 dp remove button + 2 x 16 dp padding. */
private val BookRowFixedWidth = 152.dp

/** Room for roughly a dozen characters of title per line at the default size. */
private val BookRowTextWidth = 148.dp

@Composable
private fun BookRow(
    book: LibraryBookItem,
    onRemove: () -> Unit,
    onRemoveFromAccount: () -> Unit,
    onRemoveAccountCopy: () -> Unit,
    onAddToAccount: () -> Unit,
    onCancelAddToAccount: () -> Unit,
    onDismissAddToAccount: () -> Unit,
    onDownloadAccountBook: () -> Unit,
    onCancelDownload: () -> Unit,
    onDismissDownload: () -> Unit,
    onGrantAccess: () -> Unit,
    onOpen: () -> Unit,
    coverLoader: CoverLoader,
    modifier: Modifier = Modifier,
) {
    val author = book.author ?: stringResource(R.string.library_unknown_author)
    val statusLine = book.statusLine()
    val openLabel = stringResource(R.string.library_open, book.title)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Sizes.ListRowMinHeight)
            // A readable book opens in the reader; the rest already explain in
            // their status line why there is nothing to open.
            .then(
                if (book.canOpen) {
                    Modifier.clickable(onClickLabel = openLabel, onClick = onOpen)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = Spacing.Large, vertical = Spacing.Medium)
            .testTag("library_book_${book.id}"),
        verticalAlignment = Alignment.Top,
    ) {
        Cover(book = book, coverLoader = coverLoader)
        Spacer(Modifier.width(Spacing.Large))
        Column(
            modifier = Modifier
                .weight(1f)
                // One TalkBack stop for the book; the actions stay separately focusable.
                .semantics(mergeDescendants = true) {},
        ) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = author,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = statusLine,
                style = MaterialTheme.typography.bodySmall,
                color = if (book.isReadable) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(top = Spacing.XSmall),
            )
            // A new slot under the status line, beside the re-grant button that
            // was already there: removing a book from the account is a different
            // act from removing its row here, so it is a different control that
            // says which one it is (REQ-508).
            if (book.account != null) {
                val accountRemoveLabel = stringResource(R.string.library_account_remove_label, book.title)
                TextButton(
                    onClick = onRemoveFromAccount,
                    modifier = Modifier
                        .defaultMinSize(minHeight = Sizes.TouchTarget)
                        .testTag("library_account_remove_${book.id}")
                        .semantics { contentDescription = accountRemoveLabel },
                    contentPadding = PaddingValues(horizontal = Spacing.Medium, vertical = Spacing.Small),
                ) {
                    Text(stringResource(R.string.library_account_remove))
                }
            }
            // The other half of the account pair, and the one that puts bytes on
            // the wire: adding this device's book to the account (REQ-505). It
            // is present only on a row that could actually be added, so every
            // signed-out row and every account row look exactly as they did.
            book.addToAccount?.let { add ->
                AddToAccountSlot(
                    book = book,
                    add = add,
                    onAdd = onAddToAccount,
                    onCancel = onCancelAddToAccount,
                    onDismiss = onDismissAddToAccount,
                )
            }
            // The other direction (REQ-510): bringing an account book's bytes
            // here. Present on an account-only row and nowhere else.
            if (book.isAccountOnly) {
                AccountDownloadSlot(
                    book = book,
                    onDownload = onDownloadAccountBook,
                    onCancel = onCancelDownload,
                    onDismiss = onDismissDownload,
                )
            }
            // And freeing those bytes again (D2). Read off the catalog's own
            // `ACCOUNT_COPY` source, so it is still here after sign-out — which
            // is exactly what "a downloaded copy is an ordinary device book,
            // openable and removable" has to mean on screen (D4).
            if (book.accountCopy != null) {
                val freeLabel = stringResource(R.string.library_account_copy_remove_label, book.title)
                TextButton(
                    onClick = onRemoveAccountCopy,
                    modifier = Modifier
                        .defaultMinSize(minHeight = Sizes.TouchTarget)
                        .testTag("library_account_copy_remove_${book.id}")
                        .semantics { contentDescription = freeLabel },
                    contentPadding = PaddingValues(horizontal = Spacing.Medium, vertical = Spacing.Small),
                ) {
                    Text(stringResource(R.string.library_account_copy_remove))
                }
            }
            if (book.status == BookStatus.PERMISSION_LOST) {
                TextButton(
                    onClick = onGrantAccess,
                    modifier = Modifier
                        .defaultMinSize(minHeight = Sizes.TouchTarget)
                        .testTag("library_grant_${book.id}"),
                    contentPadding = PaddingValues(horizontal = Spacing.Medium, vertical = Spacing.Small),
                ) {
                    Text(stringResource(R.string.library_grant_access))
                }
            }
        }
        Spacer(Modifier.width(Spacing.Small))
        // Removing the *row* is removing this device's copy from this device's
        // library, and an account-only row has no copy here to remove: its only
        // removal is the account one above. A downloaded copy has one too, and
        // it is the one above that frees the bytes — offering this as well
        // would let a reader drop the row and leave the file behind, which is
        // the one way "removable" could be untrue. Every other device row keeps
        // this button exactly as it was.
        if (!book.isAccountOnly && book.accountCopy == null) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(Sizes.TouchTarget).testTag("library_remove_${book.id}"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Clear,
                    contentDescription = stringResource(R.string.library_remove, book.title),
                )
            }
        }
    }
}

@Composable
private fun LibraryBookItem.statusLine(): String = when {
    // The account has this book and this device does not. Said plainly; what
    // to do about it is the control under this line (REQ-510).
    isAccountOnly -> stringResource(R.string.library_account_not_on_device)

    else -> deviceStatusLine()
}

@Composable
private fun LibraryBookItem.deviceStatusLine(): String = when (status) {
    // One line, two numbers, and only when the second one says something: the
    // account's place is shown beside this device's while it is ahead of it
    // (REQ-511). Which number is *the* position is not in question — the
    // device's is, until the reader answers the offer in the reader.
    BookStatus.READABLE ->
        accountPercentAhead
            ?.let { stringResource(R.string.library_progress_account_ahead, progressPercent, it) }
            ?: stringResource(R.string.library_progress, progressPercent)

    BookStatus.CORRUPT -> stringResource(R.string.library_state_corrupt)

    BookStatus.DRM_PROTECTED -> stringResource(R.string.library_state_drm)

    BookStatus.MISSING -> stringResource(R.string.library_state_missing)

    BookStatus.PERMISSION_LOST -> stringResource(R.string.library_state_permission_lost)
}

@Composable
private fun Cover(book: LibraryBookItem, coverLoader: CoverLoader) {
    val image: ImageBitmap? by produceState<ImageBitmap?>(null, book.id, book.hasCover, coverLoader) {
        value = if (book.hasCover) coverLoader.load(book.id) else null
    }
    val shape = RoundedCornerShape(4.dp)
    val bitmap = image
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = CoverWidth, height = CoverHeight)
                .clip(shape)
                // Decorative, like the placeholder: otherwise TalkBack says the
                // title twice, and only for the books that happen to have art.
                .clearAndSetSemantics {},
        )
    } else {
        Box(
            modifier = Modifier
                .size(width = CoverWidth, height = CoverHeight)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                // Decorative: the title next to it already says which book this is.
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = book.coverInitial,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A book's cover on the shelf: a paperback's proportions at list-row size. */
private val CoverWidth = 48.dp
private val CoverHeight = 64.dp
