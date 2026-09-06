package eu.kanade.tachiyomi.ui.main

import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.more.OnboardingScreen
import koharia.source.local.LocalFolderSettingsScreen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MainStartupNavigationTest {
    @Test
    fun `first frame targets onboarding while home stays underneath for completion`() {
        val screens = initialMainScreens(false)
        assertEquals(2, screens.size)
        assertSame(HomeScreen, screens.first())
        assertTrue(screens.last() is OnboardingScreen)
        assertFalse(needsInitialOnboarding(false, screens))
    }

    @Test
    fun `completed onboarding starts directly at home`() {
        assertEquals(listOf(HomeScreen), initialMainScreens(true))
        assertFalse(needsInitialOnboarding(true, listOf(HomeScreen)))
    }

    @Test
    fun `restored home is gated but onboarding child settings are not interrupted`() {
        assertTrue(needsInitialOnboarding(false, listOf(HomeScreen)))
        val setup = LocalFolderSettingsScreen(42, "Local", null)
        assertFalse(needsInitialOnboarding(false, listOf(HomeScreen, OnboardingScreen(), setup)))
    }
}
