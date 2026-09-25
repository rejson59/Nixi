package dev.nixi.store

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES w Android Keystore — plik w Pobranych bez gołych kluczy API. */
object ConfigCrypto {

    private const val ALIAS = "nixi_config_aes"
    private const val PREFIX = "NIXI1."

    fun wrap(plain: String): String {
        val key = secret()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        val iv = c.iv
        val enc = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        val all = ByteArray(iv.size + enc.size)
        System.arraycopy(iv, 0, all, 0, iv.size)
        System.arraycopy(enc, 0, all, iv.size, enc.size)
        return PREFIX + Base64.encodeToString(all, Base64.NO_WRAP)
    }

    fun unwrap(raw: String): String {
        val t = raw.trim()
        if (!t.startsWith(PREFIX)) return t
        val all = Base64.decode(t.removePrefix(PREFIX), Base64.NO_WRAP)
        if (all.size < 13) return t
        val iv = all.copyOfRange(0, 12)
        val enc = all.copyOfRange(12, all.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, iv))
        return String(c.doFinal(enc), Charsets.UTF_8)
    }

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply {
                if (Build.VERSION.SDK_INT >= 28) setUnlockedDeviceRequired(false)
            }
            .build()
        gen.init(spec)
        return gen.generateKey()
    }
}
