package com.selbir.authenticator.data

import android.content.Context

/** Simple key-value store for one-time flags. */
class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    fun hasShownBackupPrompt(): Boolean = prefs.getBoolean(KEY_BACKUP_PROMPT, false)

    fun markBackupPromptShown() = prefs.edit().putBoolean(KEY_BACKUP_PROMPT, true).apply()

    private companion object {
        const val KEY_BACKUP_PROMPT = "backup_prompt_shown"
    }
}
