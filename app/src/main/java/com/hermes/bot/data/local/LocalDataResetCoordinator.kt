package com.hermes.bot.data.local

/**
 * Plan CACHE-PR5 FILE-04: the coordinator sits at the composition root —
 * outside Compose, behind AppContainer (FGU-004: view models own deferred
 * collection wiring, but cross-screen destructive flows get one composition
 * root seam so Settings (no view model today) can't bypass confirmation).
 *
 * The two operations are the ONLY entry points to the local reset flows, and
 * both return the residue-carrying [CacheClearResult] so a failed clear is
 * representable (REQ-02/OQ-02) instead of silent. No protocol-level,
 * identity, or session-termination terms appear here (SEC-04): the
 * coordinator knows only VaultStorage's local reset verbs.
 */
interface LocalDataResetCoordinator {
    /** Seven-phase all-cache clear (REQ-02) — never credentials, never network settings. */
    suspend fun clearOfflineCache(): CacheClearResult

    /** Full local wipe (REQ-04) — callers must run the local sign-out teardown FIRST (REQ-07). */
    suspend fun clearAllLocalData(): CacheClearResult
}

class DefaultLocalDataResetCoordinator(
    private val vault: VaultStorage,
    private val clearMemory: () -> Unit = {},
) : LocalDataResetCoordinator {
    override suspend fun clearOfflineCache(): CacheClearResult {
        clearMemory()
        return try { vault.clearCache() } finally { clearMemory() }
    }
    override suspend fun clearAllLocalData(): CacheClearResult {
        clearMemory()
        return try { vault.clearAll() } finally { clearMemory() }
    }
}
