package com.ucfvpn.app.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class MainActivityTest {

    // The tab icons carry their contentDescription inside NavigationBarItem,
    // which merges its children's semantics. The nodes therefore only exist in
    // the unmerged tree, so every finder below needs useUnmergedTree = true.

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun statusScreen_showsDisconnectedByDefault() {
        composeTestRule.onNodeWithText("Disconnected").assertIsDisplayed()
        composeTestRule.onNodeWithText("Connect").assertIsDisplayed()
    }

    @Test
    fun configScreen_rendersFormFields() {
        // Navigate to Config tab
        composeTestRule.onNodeWithContentDescription("Config", useUnmergedTree = true).performClick()

        // Only what is on screen can be asserted as displayed.
        composeTestRule.onNodeWithText("Configuration").assertIsDisplayed()
        composeTestRule.onNodeWithText("SSTP Configuration").assertIsDisplayed()

        // The rest of the form lives below the fold: ConfigScreen is a scrolling
        // column, so asserting "displayed" on sections further down could never
        // pass. Existence in the tree is the right assertion here.
        composeTestRule.onNodeWithText("Proxy Configuration").assertExists()
        composeTestRule.onNodeWithText("wstunnel Configuration").assertExists()
        composeTestRule.onNodeWithText("Split Tunnel Configuration").assertExists()
        composeTestRule.onNodeWithText("Save Configuration").assertExists()
    }

    @Test
    fun navigation_switchesBetweenTabs() {
        // Default is Status tab
        composeTestRule.onNodeWithText("VPN Status").assertIsDisplayed()

        // Switch to Config tab
        composeTestRule.onNodeWithContentDescription("Config", useUnmergedTree = true).performClick()
        composeTestRule.onNodeWithText("Configuration").assertIsDisplayed()

        // Switch to Logs tab
        composeTestRule.onNodeWithContentDescription("Logs", useUnmergedTree = true).performClick()
        composeTestRule.onNodeWithText("Connection Logs").assertIsDisplayed()

        // Switch back to Status
        composeTestRule.onNodeWithContentDescription("Status", useUnmergedTree = true).performClick()
        composeTestRule.onNodeWithText("VPN Status").assertIsDisplayed()
    }

    @Test
    fun logScreen_showsInitialState() {
        // Navigate to Logs tab
        composeTestRule.onNodeWithContentDescription("Logs", useUnmergedTree = true).performClick()

        composeTestRule.onNodeWithText("Connection Logs").assertIsDisplayed()

        // The log is NOT empty on first open: VpnOrchestrator collects the state
        // machine from its init block, so the initial Disconnected state is
        // logged before this screen is ever shown. The old test asserted
        // "No log entries yet", which the app can never display in practice.
        composeTestRule.onNodeWithText("No log entries yet").assertDoesNotExist()
        composeTestRule.onNodeWithText("Disconnected", substring = true).assertExists()
    }

    @Test
    fun connectButton_isEnabledAndClickable() {
        // The click itself is deliberately NOT performed: it asks Android for VPN
        // consent, and that system dialog covers the Activity, leaving no compose
        // hierarchy to inspect ("No compose hierarchies found in the app"). An
        // instrumentation run cannot get past that dialog, so what is verifiable
        // here is that the button is present and actually wired to an action.
        composeTestRule.onNodeWithText("Connect")
            .assertIsDisplayed()
            .assertHasClickAction()
    }
}
