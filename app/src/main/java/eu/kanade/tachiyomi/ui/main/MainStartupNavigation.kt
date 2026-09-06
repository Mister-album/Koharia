package eu.kanade.tachiyomi.ui.main

import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.more.OnboardingScreen

internal fun initialMainScreens(onboardingComplete: Boolean): List<Screen> =
    if (onboardingComplete) listOf(HomeScreen) else listOf(HomeScreen, OnboardingScreen())

internal fun needsInitialOnboarding(onboardingComplete: Boolean, screens: List<Screen>): Boolean =
    !onboardingComplete && screens.none { it is OnboardingScreen }
