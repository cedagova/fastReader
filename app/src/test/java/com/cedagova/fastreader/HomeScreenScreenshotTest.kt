package com.cedagova.fastreader

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.ui.HomeScreen
import com.cedagova.fastreader.ui.theme.FastReaderTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HomeScreenScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun homeScreen_light() {
        composeRule.setContent {
            FastReaderTheme(darkTheme = false) {
                HomeScreen()
            }
        }
        composeRule.onRoot().captureRoboImage("screenshots/home_light.png")
    }

    @Test
    fun homeScreen_dark() {
        composeRule.setContent {
            FastReaderTheme(darkTheme = true) {
                HomeScreen()
            }
        }
        composeRule.onRoot().captureRoboImage("screenshots/home_dark.png")
    }
}
