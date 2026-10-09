package com.hermes.bot.feature.settings

/** In-memory presentation only: no persistence, host mutation, or connection orchestration. */
internal enum class SettingsSheet { APPEARANCE, CONNECTIONS, PRIVACY }

internal data class SettingsPresentationState(
    val sheet: SettingsSheet? = null,
    val pendingResetChoice: SettingsResetChoice? = null,
    val pendingRemoveProfileId: String? = null
) {
    fun openSheet(sheet: SettingsSheet) = SettingsPresentationState(sheet = sheet)

    fun requestReset(choice: SettingsResetChoice): SettingsPresentationState =
        if (sheet == SettingsSheet.PRIVACY) copy(pendingResetChoice = choice) else this

    fun requestRemoval(localId: String, profileCount: Int): SettingsPresentationState =
        if (sheet == SettingsSheet.CONNECTIONS && localId.isNotBlank() &&
            ProfileRemoveConfirmationPolicy.canRemove(profileCount)) {
            copy(pendingRemoveProfileId = localId)
        } else this

    fun dismiss(): SettingsPresentationState = when {
        pendingResetChoice != null || pendingRemoveProfileId != null ->
            copy(pendingResetChoice = null, pendingRemoveProfileId = null)
        else -> SettingsPresentationState()
    }

    /** Consume the pending intent before the UI invokes its existing reset callback. */
    fun confirmReset(): Pair<SettingsPresentationState, SettingsResetChoice?> =
        copy(pendingResetChoice = null) to pendingResetChoice

    /** Revalidate the current roster so an obsolete/last connection cannot be removed. */
    fun confirmRemoval(currentIds: List<String>): Pair<SettingsPresentationState, String?> {
        val id = pendingRemoveProfileId?.takeIf {
            it in currentIds && ProfileRemoveConfirmationPolicy.canRemove(currentIds.size)
        }
        return copy(pendingRemoveProfileId = null) to id
    }
}
