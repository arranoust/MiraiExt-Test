package com.arranoust

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper

object PopupHelper {
    private const val PREFS_NAME = "MiraiExtPrefs"
    private const val KEY_SHOWN = "first_time_popup_v1"
    private const val REPO_URL = "https://github.com/arranoust/MiraiExt"

    fun showWelcome(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SHOWN, false)) return

        prefs.edit().putBoolean(KEY_SHOWN, true).apply()

        Handler(Looper.getMainLooper()).post {
            try {
                AlertDialog.Builder(context)
                    .setTitle("MiraiExt")
                    .setMessage("Selamat menonton! Dukung project ini dengan memberikan Star di GitHub ya. ⭐")
                    .setPositiveButton("Mulai") { dialog, _ -> 
                        dialog.dismiss() 
                    }
                    .setNeutralButton("Kunjungi Github") { _, _ ->
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            // Kosongkan jika gagal buka browser
                        }
                    }
                    .setCancelable(false)
                    .show()
            } catch (e: Exception) {
                // Mencegah crash jika context tidak valid
            }
        }
    }
}