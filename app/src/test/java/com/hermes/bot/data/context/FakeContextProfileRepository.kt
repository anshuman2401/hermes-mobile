package com.hermes.bot.data.context

import com.hermes.bot.data.gateway.SessionSummary
import com.hermes.bot.data.local.AuthCredentialLease

/**
 * PR-1 review (BLOCKER-3) test fixture: a [ContextProfileRepository] whose
 * scoped-fetch result is scripted, with an optional hook that mutates vault
 * state (e.g. the workspace selection) AFTER the caller has resolved its
 * partition but BEFORE the fetch result returns — simulating a mid-flight
 * selection change.
 */
class FakeContextProfileRepository(
    var scopedResult: ScopedSessionsResult = ScopedSessionsResult.Success(emptyList())
) : ContextProfileRepository {
    var profilesResult: ContextProfilesResult = ContextProfilesResult.Error(ContextProfileErrorReason.UNAVAILABLE, "not scripted")

    /** Runs inside [getScopedSessionsTyped] before the scripted result returns. */
    var onScopedFetch: (suspend () -> Unit)? = null

    override suspend fun getScopedSessionsTyped(profile: String, limit: Int): ScopedSessionsResult {
        return scriptedScopedFetch()
    }

    override suspend fun getScopedSessionsCaptured(
        profile: String,
        expectedLease: AuthCredentialLease,
        limit: Int
    ): ScopedSessionsResult = scriptedScopedFetch()

    private suspend fun scriptedScopedFetch(): ScopedSessionsResult {
        onScopedFetch?.invoke()
        return scopedResult
    }

    override suspend fun getScopedSessions(
        profile: String,
        limit: Int
    ): Result<List<SessionSummary>> =
        Result.failure(UnsupportedOperationException("not used by PR-1 tests"))

    override suspend fun getProfiles(): ContextProfilesResult = profilesResult

    override suspend fun selectProfile(name: String): ContextProfileSelectionResult =
        ContextProfileSelectionResult.Success(name)

    override suspend fun getSelectedProfile(): String? = null

    override suspend fun setSelectedProfile(name: String?) = Unit
}
