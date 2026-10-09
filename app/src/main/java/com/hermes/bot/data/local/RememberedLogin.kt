package com.hermes.bot.data.local

/** Process-local operation receipt. Never display or persist this object. */
class LoginOwner internal constructor(
    internal val profileId: String,
    internal val origin: String,
    internal val generation: Long,
    internal val authEpoch: String?
) { override fun toString() = "LoginOwner([redacted])" }

/** Secrets remain behind the repository boundary; callers close each borrowed value. */
class RememberedLogin internal constructor(
    internal val username: String,
    internal val password: CharArray,
    internal val provider: String
) : AutoCloseable {
    override fun close() { password.fill('\u0000') }
    override fun toString() = "RememberedLogin([redacted])"
}
