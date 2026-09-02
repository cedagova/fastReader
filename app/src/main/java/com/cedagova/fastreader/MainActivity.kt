package com.cedagova.fastreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.cedagova.fastreader.library.ui.LibraryRoute
import com.cedagova.fastreader.ui.theme.FastReaderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val library = (application as FastReaderApplication).library
        setContent {
            FastReaderTheme {
                LibraryRoute(library)
            }
        }
    }
}
