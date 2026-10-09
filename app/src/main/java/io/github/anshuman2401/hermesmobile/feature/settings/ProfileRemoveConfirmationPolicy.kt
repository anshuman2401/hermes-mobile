package io.github.anshuman2401.hermesmobile.feature.settings

import io.github.anshuman2401.hermesmobile.data.local.ConnectionProfile

/**
 * Pure JVM policy governing profile removal confirmation dialog semantics (MEDIUM-3).
 */
object ProfileRemoveConfirmationPolicy {

    /**
     * Dialog title for connection removal.
     */
    fun confirmationTitle(): String {
        return "Remove connection"
    }

    /**
     * Produces the confirmation prompt: "Remove connection X? This deletes its saved sign-in."
     */
    fun confirmationMessage(displayLabel: String): String {
        return "Remove connection $displayLabel? This deletes its saved sign-in."
    }

    /**
     * Resolves the user-facing display label for a profile (displayLabel if non-blank, else normalizedOrigin).
     */
    fun displayLabel(profile: ConnectionProfile): String {
        return profile.displayLabel.trim().ifBlank { profile.normalizedOrigin.trim() }
    }

    /**
     * Decides whether removal is permitted (only when more than 1 profile exists).
     */
    fun canRemove(profileCount: Int): Boolean {
        return profileCount > 1
    }
}
