// SPDX-License-Identifier: GPL-3.0-or-later
package org.hdricapture.settings

import android.content.Context
import org.hdricapture.session.RigMode

/** User preferences, persisted in SharedPreferences. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var lensId: String?
        get() = prefs.getString("lens", null)
        set(v) = prefs.edit().putString("lens", v).apply()

    var rigMode: RigMode
        get() = runCatching { RigMode.valueOf(prefs.getString("rig", null)!!) }.getOrDefault(RigMode.HANDHELD)
        set(v) = prefs.edit().putString("rig", v.name).apply()

    var bracketFrames: Int
        get() = prefs.getInt("frames", 5)
        set(v) = prefs.edit().putInt("frames", v).apply()

    var bracketStepEv: Float
        get() = prefs.getFloat("step", 2f)
        set(v) = prefs.edit().putFloat("step", v).apply()

    var overlap: Float
        get() = prefs.getFloat("overlap", 0.3f)
        set(v) = prefs.edit().putFloat("overlap", v).apply()


    var handheldTipShown: Boolean
        get() = prefs.getBoolean("tip", false)
        set(v) = prefs.edit().putBoolean("tip", v).apply()
}
