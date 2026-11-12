package br.com.medidor.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-bound signed receipts only; a copied backup cannot grant access on another device. */
internal class ProReceiptCache(context: Context) {
    private val prefs = context.getSharedPreferences("play_receipt_v1", Context.MODE_PRIVATE)
    private val alias = "metricas_play_receipt"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(receipt: String, signature: String) {
        val json = JSONObject().put("receipt", receipt).put("signature", signature)
            .put("checked", System.currentTimeMillis()).toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encoded = Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(json))
        check(prefs.edit().putString("receipt", encoded).commit())
    }
    fun read(): Pair<String, String>? = runCatching {
        val encoded = prefs.getString("receipt", null) ?: return null
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        val data = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        require(System.currentTimeMillis() - data.getLong("checked") in 0..7L * 24 * 60 * 60 * 1000)
        data.getString("receipt") to data.getString("signature")
    }.getOrNull()
    fun clear() { prefs.edit().remove("receipt").commit() }
}
