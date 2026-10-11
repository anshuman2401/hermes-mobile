package com.hermes.bot.data.update

/** Fixed, non-secret presentation categories. No raw host error text is retained. */
enum class HostUpdateFailure(val message: String) {
    UNAVAILABLE("Could not reach Hermes. Check the connection and try again."),
    TIMEOUT("Hermes took too long to respond. Check again."),
    STATUS_UNAVAILABLE("Update status is unavailable. Update controls are disabled; check again later."),
    AUTH("Sign in again to check host updates."),
    UNSUPPORTED("This host does not support update controls."),
    MALFORMED("Hermes returned an unsupported update response."),
    STALE("The connection changed. Reopen Updates to check again."),
    ORIGIN("Update request rejected by the exact-origin policy."),
    NOT_ELIGIBLE("This host cannot apply an update from the app."),
    INTERRUPTED("The host update was interrupted. Check again before trying another update."),
    REFUSED("Hermes declined to start the update."),
    UNKNOWN("The update result is unknown. Check the host before trying again.")
}

sealed interface HostUpdateResult<out T> {
    class Ready<T>(val value: T) : HostUpdateResult<T> { override fun toString() = "HostUpdateResult.Ready([private])" }
    data class Failed(val reason: HostUpdateFailure) : HostUpdateResult<Nothing>
}

data class HostUpdateCheck(
    val installedVersion: String,
    val available: Boolean,
    val behind: Int?,
    val canApply: Boolean,
    val installMethod: String,
    val changes: List<String>
) {
    val appUpdateEligible: Boolean get() = available && canApply && installMethod == "git"
    override fun toString() = "HostUpdateCheck([private])"
}

data class HostUpdateStart(val actionId: String) { override fun toString() = "HostUpdateStart([private])" }
data class HostUpdateStatus(val running: Boolean, val actionId: String?, val outcome: String?) {
    override fun toString() = "HostUpdateStatus([private])"
}
