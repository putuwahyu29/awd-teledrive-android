package com.awd.teledrive.data.secure

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EncryptionManager @Inject constructor() {
    private val ALGORITHM = "AES/GCM/NoPadding"
    private val TAG_LENGTH_BIT = 128
    private val IV_LENGTH_BYTE = 12
    private val SALT_LENGTH_BYTE = 16
    private val ITERATION_COUNT = 10000
    private val KEY_LENGTH_BIT = 256

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH_BIT)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    fun encryptFile(inputFile: File, outputFile: File, password: String) {
        val salt = ByteArray(SALT_LENGTH_BYTE)
        SecureRandom().nextBytes(salt)
        
        val iv = ByteArray(IV_LENGTH_BYTE)
        SecureRandom().nextBytes(iv)
        
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BIT, iv))
        
        FileOutputStream(outputFile).use { fos ->
            // Write metadata first: Salt then IV
            fos.write(salt)
            fos.write(iv)
            
            CipherOutputStream(fos, cipher).use { cos ->
                inputFile.inputStream().use { fis ->
                    fis.copyTo(cos)
                }
            }
        }
    }

    fun decryptFile(encryptedFile: File, outputFile: File, password: String) {
        FileInputStream(encryptedFile).use { fis ->
            val salt = ByteArray(SALT_LENGTH_BYTE)
            val saltRead = fis.read(salt)
            if (saltRead != SALT_LENGTH_BYTE) throw Exception("Invalid salt in encrypted file")
            
            val iv = ByteArray(IV_LENGTH_BYTE)
            val ivRead = fis.read(iv)
            if (ivRead != IV_LENGTH_BYTE) throw Exception("Invalid IV in encrypted file")
            
            val key = deriveKey(password, salt)
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BIT, iv))
            
            CipherInputStream(fis, cipher).use { cis ->
                FileOutputStream(outputFile).use { fos ->
                    cis.copyTo(fos)
                }
            }
        }
    }
}
