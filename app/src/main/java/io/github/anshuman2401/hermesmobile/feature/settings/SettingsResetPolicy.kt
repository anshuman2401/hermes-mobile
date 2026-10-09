package io.github.anshuman2401.hermesmobile.feature.settings

/**
 * Plan CACHE-PR5 FILE-02 / REQ-01: the two Settings local-reset choices and
 * their confirmation-dialog copy — a pure JVM policy object (mirrors
 * ProfileRemoveConfirmationPolicy: no Compose, no storage). The screen owns
 * the state machine (IDLE/PENDING/CONFIRMED/CANCEL); this object only maps
 * a choice to its binding strings (plan Interfaces, line 76). Cancel is
 * representable only as the abort path: [ConfirmationCopy.cancelLabel] and a
 * null decision in the screen — no callback fires without CONFIRMED.
 */
enum class SettingsResetChoice {
    CLEAR_OFFLINE_CACHE,
    CLEAR_ALL_LOCAL_DATA,
}

data class ConfirmationCopy(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val cancelLabel: String,
)

object SettingsResetPolicy {

    /** Exhaustive per-choice mapping — adding an enum entry without copy is a compile error. */
    fun copyFor(choice: SettingsResetChoice): ConfirmationCopy = when (choice) {
        SettingsResetChoice.CLEAR_OFFLINE_CACHE -> ConfirmationCopy(
            title = "Clear offline cache?",
            message = "Saved lists, workspace rosters, and conversations stored on this " +
                "device will be removed. Drafts, pending turns, sign-in, and your " +
                "Hermes sessions on the server are not affected. New data is " +
                "saved again as you use the app.",
            confirmLabel = "Clear offline cache",
            cancelLabel = "Cancel",
        )

        SettingsResetChoice.CLEAR_ALL_LOCAL_DATA -> ConfirmationCopy(
            title = "Clear all local app data?",
            message = "Erases everything this app stored on this device, including the " +
                "offline cache (saved lists, workspace rosters, and " +
                "conversations), drafts, pending turns, saved connections, and " +
                "sign-in. Your Hermes sessions on the server are not deleted. " +
                "You will set up the connection again.",
            confirmLabel = "Erase all",
            cancelLabel = "Cancel",
        )
    }
}
