package com.assavendo.machinevirtuel.ui

import android.content.Context

data class GestureSettings(
    val enabled: Boolean = true,
    val edgeSwipeEnabled: Boolean = true,
    val pinchEnabled: Boolean = true,
    val threeFingerTapEnabled: Boolean = false,
)

object GestureSettingsStore {
    private const val PREFS = "phoenixvm_gestures"

    fun load(context: Context): GestureSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return GestureSettings(
            enabled = prefs.getBoolean("enabled", true),
            edgeSwipeEnabled = prefs.getBoolean("edge_swipe", true),
            pinchEnabled = prefs.getBoolean("pinch", true),
            threeFingerTapEnabled = prefs.getBoolean("three_finger_tap", false),
        )
    }

    fun save(context: Context, settings: GestureSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", settings.enabled)
            .putBoolean("edge_swipe", settings.edgeSwipeEnabled)
            .putBoolean("pinch", settings.pinchEnabled)
            .putBoolean("three_finger_tap", settings.threeFingerTapEnabled)
            .apply()
    }
}
