package br.app.assinador

import android.content.Context

/** Lembra posição do selo e página para que o uso diário seja um toque. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("selo", Context.MODE_PRIVATE)
    var x: Float get() = sp.getFloat("x", 0.06f); set(v) = sp.edit().putFloat("x", v).apply()
    var y: Float get() = sp.getFloat("y", 0.88f); set(v) = sp.edit().putFloat("y", v).apply()
    var lastPage: Boolean get() = sp.getBoolean("last", true); set(v) = sp.edit().putBoolean("last", v).apply()
    var visible: Boolean get() = sp.getBoolean("visible", true); set(v) = sp.edit().putBoolean("visible", v).apply()
}
