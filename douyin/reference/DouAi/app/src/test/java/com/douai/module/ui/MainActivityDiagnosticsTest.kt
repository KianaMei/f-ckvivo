package com.douai.module.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import com.douai.module.MainActivity
import com.douai.module.config.ModuleConfigRuntime
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A missing framework service must not prevent reading the host receipt channel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityDiagnosticsTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun noFrameworkConnectionStillProducesAnExplicitReceiptResult() {
        compose.waitForIdle()
        compose.runOnUiThread {
            assertFalse(ModuleConfigRuntime.get(compose.activity).sync().isConnected)
        }
        compose.onNodeWithText("未收到抖音注入回执").assertExists()
        compose.onNode(hasText("功能设置") and hasClickAction()).assertExists()
    }
}
