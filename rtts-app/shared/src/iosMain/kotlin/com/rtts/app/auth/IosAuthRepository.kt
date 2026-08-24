package com.rtts.app.auth

/**
 * TODO(iOS): implement PIN storage for iOS.
 *
 * Not implemented yet because it needs two decisions that only make sense with Xcode/a Mac
 * in hand to verify:
 *  1. Persistence: NSUserDefaults is fine for a single-PIN MVP; Keychain is the more correct
 *     place for secret material long-term.
 *  2. Hashing: the Android side uses javax.crypto (PBKDF2), which does not exist on
 *     Kotlin/Native. Either add a pure-Kotlin KMP crypto library (e.g. Ionspin's
 *     multiplatform-crypto) to commonMain so both platforms share one implementation, or
 *     call into CryptoKit via a cinterop binding on the iOS side.
 */
class IosAuthRepository : AuthRepository {
    override suspend fun hasAnyUser(): Boolean =
        throw NotImplementedError("IosAuthRepository: pending PIN storage decision, see class doc")

    override suspend fun createUser(username: String, pin: String): Unit =
        throw NotImplementedError("IosAuthRepository: pending PIN storage decision, see class doc")

    override suspend fun verifyPin(username: String, pin: String): Boolean =
        throw NotImplementedError("IosAuthRepository: pending PIN storage decision, see class doc")
}
