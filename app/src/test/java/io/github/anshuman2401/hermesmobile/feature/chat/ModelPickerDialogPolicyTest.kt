package io.github.anshuman2401.hermesmobile.feature.chat

import io.github.anshuman2401.hermesmobile.feature.chat.components.modelPickerDialogHeightPx
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelPickerDialogPolicyTest {
    @Test fun heightMatchesSettingsDialogAndClampsForKeyboard() {
        assertEquals(600, modelPickerDialogHeightPx(1000, 0, 0, 0))
        assertEquals(600, modelPickerDialogHeightPx(1000, 40, 20, 0))
        assertEquals(460, modelPickerDialogHeightPx(1000, 40, 20, 500))
        assertEquals(360, modelPickerDialogHeightPx(1000, 40, 20, 600))
    }
}
