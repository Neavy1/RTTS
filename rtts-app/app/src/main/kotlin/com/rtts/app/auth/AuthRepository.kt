package com.rtts.app.auth

import com.rtts.app.data.AppDatabase
import com.rtts.app.data.UserEntity
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private const val PBKDF2_ITERATIONS = 120_000
private const val KEY_LENGTH_BITS = 256

/**
 * Local-only auth for one or a few fixed operators: no backend, PIN hashed with PBKDF2
 * and stored in Room. Good enough for a single-tablet operational deployment.
 */
class AuthRepository(private val db: AppDatabase) {

    suspend fun hasAnyUser(): Boolean = db.userDao().getFirstUser() != null

    suspend fun createUser(username: String, pin: String): UserEntity {
        val salt = randomSalt()
        val hash = hashPin(pin, salt)
        val user = UserEntity(username = username, pinHash = hash, salt = salt)
        val id = db.userDao().insert(user)
        return user.copy(id = id)
    }

    suspend fun verifyPin(username: String, pin: String): Boolean {
        val user = db.userDao().findByUsername(username) ?: return false
        return hashPin(pin, user.salt) == user.pinHash
    }

    private fun randomSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hashPin(pin: String, salt: String): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt.toByteArray(), PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        return hash.joinToString("") { "%02x".format(it) }
    }
}
