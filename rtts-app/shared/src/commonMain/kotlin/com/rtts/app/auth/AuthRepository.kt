package com.rtts.app.auth

/**
 * Local-only auth for one or a few fixed operators: no backend, PIN-based.
 * Platform-specific storage (Room on Android; TBD on iOS) lives behind this contract so the
 * login UI in commonMain never depends on a concrete persistence mechanism.
 */
interface AuthRepository {
    suspend fun hasAnyUser(): Boolean
    suspend fun createUser(username: String, pin: String)
    suspend fun verifyPin(username: String, pin: String): Boolean
}
