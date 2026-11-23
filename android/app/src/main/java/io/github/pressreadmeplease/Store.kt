package io.github.pressreadmeplease

import android.content.Context

data class Config(
    val mlolSite: String,
    val mlolUser: String,
    val mlolPass: String,
    val prUser: String,
    val prPass: String,
    /** Login MLOL manuale (SPID): utente/password MLOL non servono. */
    val spid: Boolean = false,
) {
    fun isComplete() =
        listOf(mlolSite, prUser, prPass).all { it.isNotBlank() } &&
            (spid || (mlolUser.isNotBlank() && mlolPass.isNotBlank()))
}

/** Salva la configurazione nelle SharedPreferences private dell'app (backup disattivato nel manifest). */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)

    fun load() = Config(
        mlolSite = prefs.getString("mlol_site", "") ?: "",
        mlolUser = prefs.getString("mlol_user", "") ?: "",
        mlolPass = prefs.getString("mlol_pass", "") ?: "",
        prUser = prefs.getString("pr_user", "") ?: "",
        prPass = prefs.getString("pr_pass", "") ?: "",
        spid = prefs.getBoolean("spid", false),
    )

    fun save(c: Config) {
        prefs.edit()
            .putString("mlol_site", c.mlolSite)
            .putString("mlol_user", c.mlolUser)
            .putString("mlol_pass", c.mlolPass)
            .putString("pr_user", c.prUser)
            .putString("pr_pass", c.prPass)
            .putBoolean("spid", c.spid)
            .apply()
    }

    var lastSuccess: Long
        get() = prefs.getLong("last_success", 0L)
        set(value) {
            prefs.edit().putLong("last_success", value).apply()
        }
}
