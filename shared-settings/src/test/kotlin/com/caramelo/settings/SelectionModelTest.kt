package com.caramelo.settings

import org.junit.Assert.*
import org.junit.Test

class SelectionModelTest {
    private val items = listOf(SelectionItem("US", "United States"), SelectionItem("GB", "United Kingdom"), SelectionItem("CI", "Côte d’Ivoire"))
    @Test fun filteringAndBulkSelectionPreserveHiddenSelections() {
        val initial = setOf("CI")
        val model = SelectionModel(items, initial)
        model.setVisible("united", true)
        assertEquals(setOf("CI", "US", "GB"), model.selection())
        model.setVisible("kingdom", false)
        assertEquals(setOf("CI", "US"), model.selection())
        assertEquals(setOf("CI"), initial)
        assertEquals(listOf("CI"), model.visible("cote").map { it.id })
        assertEquals(listOf("US"), model.visible("united us").map { it.id })
    }
    @Test fun cancelUsesIndependentDraftAndUnknownIdsAreIgnored() {
        val initial = mutableSetOf("US", "deleted")
        val model = SelectionModel(items, initial)
        model.set("GB", true); model.set("unknown", true)
        assertEquals(setOf("US", "GB"), model.selection())
        assertEquals(setOf("US", "deleted"), initial)
        assertTrue(model.visible("no-match").isEmpty())
        model.setVisible("no-match", false)
        assertEquals(setOf("US", "GB"), model.selection())
    }
}
