package dev.shizzi

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import datapath.Datapath
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class PortalTlsStore(context: Context) {
    private val prefs = context.getSharedPreferences("shizzi_portal_tls", Context.MODE_PRIVATE)
    private val alias = "shizzi-portal-identity-v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    @Synchronized
    fun identity(): JSONObject {
        val stored = prefs.getString("encrypted_identity", null)
        if (stored != null) {
            val blob = JSONObject(stored)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(blob.getString("iv"), Base64.NO_WRAP)))
            return JSONObject(String(cipher.doFinal(Base64.decode(blob.getString("data"), Base64.NO_WRAP)), Charsets.UTF_8))
        }
        val generated = Datapath.generatePortalTLSIdentity()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(generated.toByteArray(Charsets.UTF_8))
        val blob = JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        check(prefs.edit().putString("encrypted_identity", blob.toString()).commit()) { "Impossible de sauvegarder l'identité du routeur" }
        return JSONObject(generated)
    }

    fun fingerprint(): String = identity().getString("fingerprint").uppercase()
}
