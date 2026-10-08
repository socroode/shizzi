package dev.shizzi

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * Local deterrent for copied router APKs. The PIN itself is never written to
 * source control, logs, preferences or the generated APK as plaintext.
 *
 * As with any short offline shared PIN, a modified APK can bypass this gate.
 */
internal object RouterPinVerifier {
    private const val ROUNDS = 210_000
    private const val SALT_HEX = "30a57e7010ab1c8cb5db1890b045619f"
    private const val PIN_HASH_HEX = "881ff952806bf517d96e037bafefbff8e9e6195107e04a7b7741f28eb8529e3b"

    fun matches(input: String): Boolean {
        if (!Regex("[0-9]{6}").matches(input)) return false
        val actual = derive(input, fromHex(SALT_HEX), ROUNDS)
        return MessageDigest.isEqual(actual, fromHex(PIN_HASH_HEX))
    }

    internal fun derive(input: String, salt: ByteArray, rounds: Int): ByteArray {
        val spec = PBEKeySpec(input.toCharArray(), salt, rounds, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun fromHex(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

internal enum class RouterActivationResult {
    ACTIVATED, ALREADY_ACTIVATED, WRONG_PIN, TOO_MANY_ATTEMPTS, STORAGE_ERROR
}

internal object RouterActivation {
    private const val PREFERENCES = "shizzi_router_activation_v1"
    private const val KEY_ALIAS = "dev.shizzi.router.activation.v1"
    private const val KEY_IV = "activation_iv"
    private const val KEY_CIPHERTEXT = "activation_cipher"
    private const val KEY_FAILED = "failed_attempts"
    private const val KEY_LOCKED_UNTIL = "locked_until"
    private const val MARKER = "shizzi-router-authorized-v1"
    private const val MAX_FAILURES = 5
    private const val LOCK_MS = 60_000L
    private val lock = Any()

    fun isActivated(context: Context): Boolean = synchronized(lock) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val iv = preferences.getString(KEY_IV, null) ?: return@synchronized false
        val ciphertext = preferences.getString(KEY_CIPHERTEXT, null)
            ?: return@synchronized false

        runCatching {
            val key = existingKey() ?: return@runCatching false
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            val decrypted = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
            MessageDigest.isEqual(decrypted, MARKER.toByteArray(Charsets.UTF_8))
        }.getOrDefault(false)
    }

    fun activate(context: Context, candidate: String): RouterActivationResult =
        synchronized(lock) {
            if (isActivated(context)) return@synchronized RouterActivationResult.ALREADY_ACTIVATED
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (now < preferences.getLong(KEY_LOCKED_UNTIL, 0L)) {
                return@synchronized RouterActivationResult.TOO_MANY_ATTEMPTS
            }
            if (!RouterPinVerifier.matches(candidate)) {
                val failures = preferences.getInt(KEY_FAILED, 0) + 1
                preferences.edit()
                    .putInt(KEY_FAILED, if (failures >= MAX_FAILURES) 0 else failures)
                    .putLong(KEY_LOCKED_UNTIL, if (failures >= MAX_FAILURES) now + LOCK_MS else 0L)
                    .commit()
                return@synchronized if (failures >= MAX_FAILURES) {
                    RouterActivationResult.TOO_MANY_ATTEMPTS
                } else {
                    RouterActivationResult.WRONG_PIN
                }
            }
            runCatching {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey())
                val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
                val ciphertext = Base64.encodeToString(
                    cipher.doFinal(MARKER.toByteArray(Charsets.UTF_8)),
                    Base64.NO_WRAP,
                )
                val written = preferences.edit()
                    .putString(KEY_IV, iv)
                    .putString(KEY_CIPHERTEXT, ciphertext)
                    .remove(KEY_FAILED)
                    .remove(KEY_LOCKED_UNTIL)
                    .commit()
                if (written) RouterActivationResult.ACTIVATED else RouterActivationResult.STORAGE_ERROR
            }.getOrDefault(RouterActivationResult.STORAGE_ERROR)
        }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun existingKey(): SecretKey? =
        keyStore().getKey(KEY_ALIAS, null) as? SecretKey

    private fun createKey(): SecretKey {
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
