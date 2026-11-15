package com.jt.notes

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Drives the real screens the way a person would: write, check, search, delete, undo, select. */
@RunWith(AndroidJUnit4::class)
class UiTests {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun unique(prefix: String) = prefix + " " + (System.nanoTime() % 1_000_000)

    private fun clickLabel(label: String) = SemanticsMatcher("click label is '$label'") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == label
    }

    private fun waitForText(text: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForTextGone(text: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }
    }

    private fun waitForFields(count: Int) {
        rule.waitUntil(10_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= count }
    }

    private fun createTextNote(title: String, body: String) {
        rule.onNodeWithContentDescription("New note").performClick()
        waitForFields(2)
        rule.onAllNodes(hasSetTextAction())[0].performTextInput(title)
        rule.onAllNodes(hasSetTextAction())[1].performTextInput(body)
        rule.onNodeWithText("Done").performClick()
        waitForText(title)
    }

    @Test
    fun writeANote_itAppearsOnHome_andSearchFindsIt() {
        val title = unique("Regression note")
        createTextNote(title, "Body with **bold** text")
        rule.onNodeWithText(title).assertIsDisplayed()

        rule.onNodeWithContentDescription("Search").performClick()
        waitForFields(1)
        rule.onNode(hasSetTextAction()).performTextInput(title.takeLast(6))
        waitForText(title)
        rule.onNode(hasSetTextAction()).performTextInput(" zzzz-no-match")
        waitForText("No matching notes")
    }

    @Test
    fun reopenANote_theTextWasSaved() {
        val title = unique("Saved note")
        createTextNote(title, "First line")
        rule.onNodeWithText(title).performClick()
        waitForFields(2)
        rule.onNodeWithText("First line").assertIsDisplayed()
        rule.onNodeWithText("Done").performClick()
        waitForText(title)
    }

    @Test
    fun checklist_checkAnItem_progressUpdates() {
        val title = unique("Regression list")
        rule.onNodeWithContentDescription("New checklist").performClick()
        waitForFields(2)
        rule.onAllNodes(hasSetTextAction())[0].performTextInput(title)
        rule.onAllNodes(hasSetTextAction())[1].performTextInput("Milk")
        waitForText("0 of 1 done")
        rule.onNode(clickLabel("Mark as done")).performClick()
        waitForText("1 of 1 done")
        rule.onNodeWithText("Done").performClick()
        waitForText(title)
        rule.onNodeWithText("1 of 1").assertIsDisplayed()
    }

    @Test
    fun deleteFromTheEditor_thenUndo_bringsTheNoteBack() {
        val title = unique("Undo note")
        createTextNote(title, "Keep me")
        rule.onNodeWithText(title).performClick()
        waitForFields(2)
        rule.onNodeWithContentDescription("Delete").performClick()
        waitForText("Moved to Trash")
        waitForTextGone(title)
        rule.onNodeWithText("Undo").performClick()
        waitForText(title)
    }

    @Test
    fun longPress_selectsANote_andArchivesIt() {
        val title = unique("Archive note")
        createTextNote(title, "Bulk action")
        rule.onNodeWithText(title).performTouchInput { longClick() }
        waitForText("1 selected")
        rule.onNodeWithContentDescription("Archive").performClick()
        waitForTextGone(title)
        // The Archive filter shows it again.
        rule.onNodeWithText("Archive").performClick()
        waitForText(title)
    }
}
