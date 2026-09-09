package com.cedagova.fastreader.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * REQ-205's breakpoint, at the one width where it can be wrong: exactly 600 dp.
 *
 * `Tablet_Low_API33` exists in the AVD matrix for this: it is exactly 600 dp wide,
 * so a rule written as `> 600.dp` — or one that compares `Dp` values that a
 * pixel round trip has moved by a fraction — gives that device the phone layout
 * and gives no other device anything to notice. These tests are the cheap gate for
 * that, and they run the boundary at five densities rather than one, because the
 * float dust the comparison has to survive appears only where `600 x density` is
 * not a whole number: 420 dpi (2.625x, the `Phone_Mid_API37` density) and 560 dpi
 * (3.5x) are the two in the matrix that are not.
 *
 * The width is what decides, never the orientation: the last case here is a phone
 * on its side, which gets the wide layout for the same reason a tablet does.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LayoutWidthTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "w600dp-h960dp-mdpi")
    fun exactlySixHundredDpIsWideAtMdpi() = assertWide(true)

    @Test
    @Config(qualifiers = "w600dp-h960dp-hdpi")
    fun exactlySixHundredDpIsWideAtHdpi() = assertWide(true)

    @Test
    @Config(qualifiers = "w600dp-h960dp-xhdpi")
    fun exactlySixHundredDpIsWideAtXhdpi() = assertWide(true)

    /** 2.625x: 600 dp is 1575 px, and a px -> dp round trip lands off 600 exactly. */
    @Test
    @Config(qualifiers = "w600dp-h960dp-420dpi")
    fun exactlySixHundredDpIsWideAtFourTwentyDpi() = assertWide(true)

    /** 3.5x, the other non-integral density in the matrix. */
    @Test
    @Config(qualifiers = "w600dp-h960dp-560dpi")
    fun exactlySixHundredDpIsWideAtFiveSixtyDpi() = assertWide(true)

    @Test
    @Config(qualifiers = "w599dp-h960dp-xhdpi")
    fun oneDpBelowTheBoundaryIsNotWide() = assertWide(false)

    @Test
    @Config(qualifiers = "w599dp-h960dp-420dpi")
    fun oneDpBelowTheBoundaryIsNotWideAtFourTwentyDpi() = assertWide(false)

    /** The reference phone in portrait: the layout every existing golden was recorded in. */
    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    fun theReferencePhoneInPortraitIsNotWide() = assertWide(false)

    /** The same phone on its side. No orientation term is involved; 914 dp is. */
    @Test
    @Config(qualifiers = "w914dp-h411dp-land-xxhdpi")
    fun theReferencePhoneInLandscapeIsWide() = assertWide(true)

    /** The narrowest phone in the matrix on its side is still past the boundary. */
    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    fun theCompactPhoneInLandscapeIsWide() = assertWide(true)

    private fun assertWide(expected: Boolean) {
        var observed: Boolean? = null
        composeRule.setContent {
            WidthAware { observed = it.wide }
        }
        composeRule.waitForIdle()
        assertEquals(expected, observed)
    }
}
