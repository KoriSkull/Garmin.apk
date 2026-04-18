package iMel9i.garminhud.lite

import android.content.Context

object HudEngineFactory {
    private const val PREFS = "HudPrefs"
    private const val KEY_BACKEND = "hud_backend"
    private const val BACKEND_LEGACY = "legacy"
    private const val BACKEND_ORIG = "orig"

    fun create(context: Context): HudEngine {
        val backend = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BACKEND, BACKEND_LEGACY)

        return if (backend == BACKEND_ORIG) {
            OrigHudEngine(context)
        } else {
            GarminHudLite(context)
        }
    }
}
