package io.github.anshuman2401.hermesmobile.data.local

/** Durable local preference, independent of credentials and offline cache expiry. */
data class SessionPinScope(val profileId: String, val originHash: String, val workspace: String?)
data class SessionPins(val scope: SessionPinScope, val sessionIds: Set<String> = emptySet())
