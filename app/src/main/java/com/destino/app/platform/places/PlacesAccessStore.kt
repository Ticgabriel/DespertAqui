package com.destino.app.platform.places

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.time.YearMonth
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlacesAccessStore @Inject constructor(@ApplicationContext context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "places-access.json"))
    private val _dialog = MutableStateFlow<String?>(null)
    val dialog = _dialog.asStateFlow()

    fun openSettings() { _dialog.value = "Sua chave de busca" }
    fun showLimit() { _dialog.value = "Limite mensal atingido" }
    fun dismiss() { _dialog.value = null }

    private fun read(): JSONObject = if (file.baseFile.exists()) {
        JSONObject(file.openRead().bufferedReader().use { it.readText() })
    } else JSONObject()

    private fun write(json: JSONObject) {
        val stream = file.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("places-personal-key", null) as? SecretKey) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder("places-personal-key",
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
    }

    @Synchronized fun personalKey(): String {
        val json = read()
        if (!json.has("key")) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128,
            Base64.decode(json.getString("iv"), Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(json.getString("key"), Base64.NO_WRAP)), Charsets.UTF_8)
    }

    @Synchronized fun saveKey(value: String) {
        val json = read()
        if (value.isBlank()) {
            json.remove("key")
            json.remove("iv")
        } else {
            require(Regex("AIza[A-Za-z0-9_-]{35}").matches(value)) { "Confira a chave: ela deve começar com AIza e ter 39 caracteres." }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secret())
            json.put("key", Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP))
            json.put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        }
        write(json)
    }

    @Synchronized fun usage(): Int {
        val json = read()
        return MonthlyQuota(json.optString("month"), json.optInt("used"))
            .current(YearMonth.now()).used
    }

    // Persist before dispatch: cancellation or process death must not refund a sent request.
    @Synchronized fun reserve(): Boolean {
        val json = read()
        val next = MonthlyQuota(json.optString("month"), json.optInt("used"))
            .reserve(YearMonth.now()) ?: return false
        json.put("month", next.month).put("used", next.used)
        write(json)
        return true
    }
}
