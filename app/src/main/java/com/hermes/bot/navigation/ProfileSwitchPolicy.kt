package com.hermes.bot.navigation

import com.hermes.bot.feature.connect.ConnectState

/**
 * Top-level sealed result types for ProfileSwitchPolicy
 * (specs/learnings/2026-09/2026-09-18-chg006-s1-nested-policy-type-in-test.md).
 */
sealed interface ProfileSwitchDecision {
    data object Allowed : ProfileSwitchDecision

    sealed interface Blocked : ProfileSwitchDecision {
        data object SameProfile : Blocked
        data object MissingProfile : Blocked
    }
}

typealias ProfileSwitchResult = ProfileSwitchDecision

/**
 * JVM-pure policy deciding whether a profile switch is permitted (plan v1, task 3).
 *
 * Contract:
 * - Allowed from every [ConnectState], including in-flight states (PREFLIGHT_LOADING, AUTH_REQUIRED),
 *   because in-flight work is cancelled prior to re-connection.
 * - Blocked when targetLocalId == activeLocalId (SameProfile).
 * - Blocked when targetLocalId is missing from available profiles (MissingProfile).
 */
object ProfileSwitchPolicy {

    fun canSwitch(
        currentConnectState: ConnectState,
        targetLocalId: String,
        activeLocalId: String?,
        availableProfileIds: Collection<String>? = null
    ): ProfileSwitchDecision {
        if (targetLocalId.isBlank()) {
            return ProfileSwitchDecision.Blocked.MissingProfile
        }
        if (targetLocalId == activeLocalId) {
            return ProfileSwitchDecision.Blocked.SameProfile
        }
        if (availableProfileIds != null && !availableProfileIds.contains(targetLocalId)) {
            return ProfileSwitchDecision.Blocked.MissingProfile
        }
        return ProfileSwitchDecision.Allowed
    }
}
