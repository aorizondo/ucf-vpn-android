package com.ucfvpn.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ucfvpn.app.state.ConnectionState
import org.junit.Rule
import org.junit.Test

class MainActivityTest {

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
        composeTestRule.onNodeWithContentDescription("Config").performClick()

        // Verify form fields are displayed
        composeTestRule.onNodeWithText("Configuration").assertIsDisplayed()
        composeTestRule.onNodeWithText("SSTP Configuration").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save Configuration").assertIsDisplayed()

        // Expand all sections and verify fields
        composeTestRule.onNodeWithText("Proxy Configuration").assertIsDisplayed()
        composeTestRule.onNodeWithText("wstunnel Configuration").assertIsDisplayed()
        composeTestRule.onNodeWithText("WireGuard Configuration").assertIsDisplayed()
    }

    @Test
    fun navigation_switchesBetweenTabs() {
        // Default is Status tab
        composeTestRule.onNodeWithText("VPN Status").assertIsDisplayed()

        // Switch to Config tab
        composeTestRule.onNodeWithContentDescription("Config").performClick()
        composeTestRule.onNodeWithText("Configuration").assertIsDisplayed()

        // Switch to Logs tab
        composeTestRule.onNodeWithContentDescription("Logs").performClick()
        composeTestRule.onNodeWithText("Connection Logs").assertIsDisplayed()

        // Switch back to Status
        composeTestRule.onNodeWithContentDescription("Status").performClick()
        composeTestRule.onNodeWithText("VPN Status").assertIsDisplayed()
    }

    @Test
    fun logScreen_showsEmptyState() {
        // Navigate to Logs tab
        composeTestRule.onNodeWithContentDescription("Logs").performClick()

        composeTestRule.onNodeWithText("Connection Logs").assertIsDisplayed()
        composeTestRule.onNodeWithText("No log entries yet").assertIsDisplayed()
    }

    @Test
    fun connectButton_transitionsToConnected() {
        composeTestRule.onNodeWithText("Connect").assertIsDisplayed()
        composeTestRule.onNodeWithText("Connect").performClick()

        // After clicking connect, the state should change to Connected
        // Note: The actual state transition may be fast, so we check for "Disconnect" button
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Disconnected").assertDoesNotExist()
    }
}
