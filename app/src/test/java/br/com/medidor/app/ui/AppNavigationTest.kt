package br.com.medidor.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppNavigationTest {
    @Test fun backReturnsThroughScanReviewAndEditorAndHomeClearsHistory() {
        var history = listOf(Destination.HOME.name)
        history = AppNavigation.open(history, Destination.ARCHITECT_SCAN)
        history = AppNavigation.open(history, Destination.ARCHITECT_REVIEW)
        history = AppNavigation.open(history, Destination.ARCHITECT)
        history = AppNavigation.back(history)
        assertEquals(Destination.ARCHITECT_REVIEW.name, history.last())
        assertEquals(history, AppNavigation.open(history, Destination.ARCHITECT_REVIEW))
        assertEquals(listOf(Destination.HOME.name), AppNavigation.open(history, Destination.HOME))
        assertEquals(listOf(Destination.HOME.name), AppNavigation.back(emptyList()))
    }
}
