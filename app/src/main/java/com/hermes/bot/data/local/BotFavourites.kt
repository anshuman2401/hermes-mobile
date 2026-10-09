package com.hermes.bot.data.local

/** Local preference identity; the issuing credential lease stays in memory only. */
class BotFavourites(
    val profileId: String,
    val origin: String,
    val names: List<String>,
    internal val owner: AuthCredentialLease
) {
    override fun toString() = "BotFavourites([redacted])"
}
