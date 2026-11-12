package br.com.medidor.app.domain

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Verifies the signed Play receipt; purchase state/product/package are checked separately. */
internal object PurchaseSignature {
    fun configured(key: String): Boolean = runCatching {
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(key.filterNot(Char::isWhitespace))))
        true
    }.getOrDefault(false)

    fun verify(key: String, receipt: String, signature: String): Boolean = runCatching {
        if (receipt.isBlank() || signature.isBlank() || receipt.length > 100_000) return false
        val public = KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(key.filterNot(Char::isWhitespace))))
        val bytes = Base64.getDecoder().decode(signature)
        listOf("SHA256withRSA", "SHA1withRSA").any { algorithm ->
            Signature.getInstance(algorithm).run {
                initVerify(public); update(receipt.toByteArray(Charsets.UTF_8)); verify(bytes)
            }
        }
    }.getOrDefault(false)
}
