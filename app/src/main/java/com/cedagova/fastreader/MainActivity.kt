package com.cedagova.fastreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.cedagova.fastreader.library.ui.LibraryRoute
import com.cedagova.fastreader.reader.ui.ReaderRoute
import com.cedagova.fastreader.ui.theme.FastReaderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val library = (application as FastReaderApplication).library
        setContent {
            FastReaderTheme {
                // Two destinations is not a navigation graph. The open book id is
                // saved instance state so a rotation keeps the reader on screen;
                // routing straight into the last-read book at launch is LEAF204.
                var openBookId by rememberSaveable { mutableStateOf<String?>(null) }
                when (val bookId = openBookId) {
                    null -> LibraryRoute(library, onOpenBook = { openBookId = it })
                    else -> ReaderRoute(library, bookId, onBack = { openBookId = null })
                }
            }
        }
    }
}
