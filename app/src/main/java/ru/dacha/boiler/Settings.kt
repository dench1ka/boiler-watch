package ru.dacha.boiler

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Alarm/capture checkpoints must be durable before a receiver returns or a process is killed.
@SuppressLint("ApplySharedPref")
class Settings(val context: Context) {
    val prefs = context.getSharedPreferences("boiler", Context.MODE_PRIVATE)
    init { LegacyUpgrade.apply(context, prefs) }
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) { prefs.edit().putBoolean("enabled", v).commit() }
    var next: Long
        get() = prefs.getLong("next", 0)
        set(v) { prefs.edit().putLong("next", v).commit() }
    var pendingCapture: Long
        get() = prefs.getLong("pendingCapture", 0)
        set(v) { prefs.edit().putLong("pendingCapture", v).commit() }
    var lastCapturedSlot: Long
        get() = prefs.getLong("lastCapturedSlot", 0)
        set(v) { prefs.edit().putLong("lastCapturedSlot", v).commit() }
    val times get() = prefs.getString("times", "09:00")!!
    val host get() = prefs.getString("host", "")!!
    val port get() = prefs.getInt("port", 465)
    val user get() = prefs.getString("user", "")!!
    val from get() = prefs.getString("from", "")!!
    val to get() = prefs.getString("to", "")!!
    val ssl get() = prefs.getBoolean("ssl", true)
    val front get() = prefs.getBoolean("front", false)
    val flash get() = prefs.getBoolean("flash", false)
    val rotation get() = prefs.getInt("rotation", 90)
    fun text(key: String) = prefs.getString(key, "—")!!
    fun status(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    fun password() = decrypt(prefs.getString("secret", "")!!)
    private fun decrypt(value: String): String {
        if (value.isEmpty()) return ""
        val parts = value.split(":")
        require(parts.size == 2) { "Сохранённый секрет повреждён. Введите его заново" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }
    fun encrypt(password: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(password.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("smtp", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("smtp", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
