package com.rtts.app.auth

import com.rtts.app.data.AppDatabase
import com.rtts.app.data.UserEntity
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private const val PBKDF2_ITERATIONS = 120_000
private const val KEY_LENGTH_BITS = 256

/** Android implementation of [AuthRepository]: PIN hashed with PBKDF2, stored in Room. */
class AndroidAuthRepository(private val db: AppDatabase) : AuthRepository {

    override suspend fun hasAnyUser(): Boolean = db.userDao().getFirstUser() != null

    override suspend fun createUser(username: String, pin: String) {
        val salt = randomSalt()
        val hash = hashPin(pin, salt)
        db.userDao().insert(UserEntity(username = username, pinHash = hash, salt = salt))
    }

    override suspend fun verifyPin(username: String, pin: String): Boolean {
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
