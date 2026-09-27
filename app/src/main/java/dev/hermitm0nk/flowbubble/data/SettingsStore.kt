package dev.hermitm0nk.flowbubble.data

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/** Only the user's own key is persisted, encrypted with a non-exportable Android Keystore key. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val alias = "flowbubble_api_key"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }
    var apiKey: String
        get() = try {
            val iv = Base64.decode(prefs.getString("key_iv", ""), Base64.NO_WRAP)
            val data = Base64.decode(prefs.getString("key_ciphertext", ""), Base64.NO_WRAP)
            if (iv.isEmpty() || data.isEmpty()) "" else Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)); String(doFinal(data), Charsets.UTF_8)
            }
        } catch (_: Exception) { "" }
        set(value) {
            if (value.isBlank()) { prefs.edit().remove("key_iv").remove("key_ciphertext").apply(); return }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            prefs.edit().putString("key_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("key_ciphertext", Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)).apply()
        }
    var bubbleSizeDp: Int
        get() = prefs.getInt("size", 58)
        set(value) { prefs.edit().putInt("size", value.coerceIn(48, 88)).apply() }
    var bubbleAlpha: Float
        get() = prefs.getFloat("alpha", .72f)
        set(value) { prefs.edit().putFloat("alpha", value.coerceIn(.25f, 1f)).apply() }
    var bubbleStyle: String
        get() = prefs.getString("style", "square") ?: "square"
        set(value) { prefs.edit().putString("style", value).apply() }
    var darkMode: Boolean
        get() = prefs.getBoolean("dark", true)
        set(value) { prefs.edit().putBoolean("dark", value).apply() }
    var bubbleX: Int
        get() = prefs.getInt("bubble_x", -1)
        set(value) { prefs.edit().putInt("bubble_x", value).apply() }
    var bubbleY: Int
        get() = prefs.getInt("bubble_y", -1)
        set(value) { prefs.edit().putInt("bubble_y", value).apply() }
}
