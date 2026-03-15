package com.voiceinput.util

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

object EncryptionUtil {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_SIZE = 256
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 16

    fun generateKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance("AES")
        keyGenerator.init(KEY_SIZE)
        return keyGenerator.generateKey()
    }

    fun keyToBase64(key: SecretKey): String {
        return Base64.encodeToString(key.encoded, Base64.NO_WRAP)
    }

    fun base64ToKey(base64Key: String): SecretKey {
        val keyBytes = Base64.decode(base64Key, Base64.NO_WRAP)
        return SecretKeySpec(keyBytes, "AES")
    }

    fun encrypt(data: ByteArray, key: SecretKey): Pair<String, String> {
        val cipher = Cipher.getInstance(ALGORITHM)
        val iv = ByteArray(GCM_IV_LENGTH)
        val secureRandom = SecureRandom()
        secureRandom.nextBytes(iv)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH * 8, iv)
        
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        val ciphertext = cipher.doFinal(data)
        
        val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val ciphertextBase64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        
        return Pair(ciphertextBase64, ivBase64)
    }

    fun decrypt(ciphertextBase64: String, ivBase64: String, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(ALGORITHM)
        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        val ciphertext = Base64.decode(ciphertextBase64, Base64.NO_WRAP)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH * 8, iv)
        
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return cipher.doFinal(ciphertext)
    }

    fun encryptString(text: String, key: SecretKey): Pair<String, String> {
        val data = text.toByteArray(Charsets.UTF_8)
        return encrypt(data, key)
    }

    fun decryptString(ciphertextBase64: String, ivBase64: String, key: SecretKey): String {
        val data = decrypt(ciphertextBase64, ivBase64, key)
        return String(data, Charsets.UTF_8)
    }
}
