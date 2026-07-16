package com.awd.teledrive.data.secure

import android.util.Log
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MasterPasswordService @Inject constructor(
    private val secureSettings: SecureSettings
) {
    private var argon2Kt: Argon2Kt? = try {
        Argon2Kt()
    } catch (e: UnsatisfiedLinkError) {
        Log.e("MasterPasswordService", "Argon2Kt native library failed to load", e)
        null
    } catch (e: Exception) {
        Log.e("MasterPasswordService", "Argon2Kt initialization failed", e)
        null
    }
    
    private val saltKey = "master_password_salt"
    private val hashKey = "master_password_hash"

    fun isPasswordSet(): Boolean {
        return secureSettings.getString(hashKey) != null
    }

    fun setPassword(password: String) {
        val salt = generateSalt()
        val hash = hashPassword(password, salt) ?: return
        secureSettings.saveString(saltKey, bytesToHex(salt))
        secureSettings.saveString(hashKey, hash)
    }

    fun verifyPassword(password: String): Boolean {
        val saltHex = secureSettings.getString(saltKey) ?: return false
        val storedHash = secureSettings.getString(hashKey) ?: return false
        val salt = hexToBytes(saltHex)
        val hash = hashPassword(password, salt)
        return hash == storedHash
    }

    private fun hashPassword(password: String, salt: ByteArray): String? {
        val argon = argon2Kt ?: return null
        return try {
            val result = argon.hash(
                mode = Argon2Mode.ARGON2_ID,
                password = password.toByteArray(),
                salt = salt,
                tCostInIterations = 2,
                mCostInKibibyte = 65536,
                parallelism = 1
            )
            result.encodedOutputAsString()
        } catch (e: Exception) {
            Log.e("MasterPasswordService", "Hashing failed", e)
            null
        }
    }

    private fun generateSalt(): ByteArray {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        return salt
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hexToBytes(hex: String): ByteArray {
        if (hex.isEmpty()) return ByteArray(0)
        val bytes = ByteArray(hex.length / 2)
        for (i in 0 until hex.length step 2) {
            bytes[i / 2] = hex.substring(i, i + 2).toInt(16).toByte()
        }
        return bytes
    }
}
