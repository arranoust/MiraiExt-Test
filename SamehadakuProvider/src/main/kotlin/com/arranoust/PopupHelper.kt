package com.arranoust // Sesuaikan dengan package provider kamu

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper

object PopupHelper {
    private const val PREFS_NAME = "MiraiExtPrefs"
    private const val KEY_SHOWN = "first_time_popup"

    fun showWelcome(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SHOWN, false)) return

        prefs.edit().putBoolean(KEY_SHOWN, true).apply()

        Handler(Looper.getMainLooper()).post {
            AlertDialog.Builder(context)
                .setTitle("Selamat Datang!")
                .setMessage("Selamat menikmati konten gratis di MiraiExt. Semoga lancar dan selamat menonton! 🍿")
                .setPositiveButton("Mulai") { dialog, _ -> dialog.dismiss() }
                .setNeutralButton("Kunjungi GitHub") { _, _ ->
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))
                        context.startActivity(intent)
                    } catch (e: Exception) {
                    }
                }
                .setCancelable(false) 
                .show()
        }
    }
}