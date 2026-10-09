package io.github.anshuman2401.hermesmobile.feature.chat

/** Requested presentation never replaces host-confirmed session fields. */
data class PickerChange(
    val runtime: String,
    val model: String? = null,
    val provider: String? = null,
    val effort: String? = null,
    val phase: Phase = Phase.WRITING
) {
    val holdsUnappliedEcho: Boolean get() = phase == Phase.WRITING || phase == Phase.DEFERRED
    enum class Phase { WRITING, CONFIRMING, DEFERRED, UNCONFIRMED }
    val notice: String get() = when (phase) {
        Phase.WRITING, Phase.CONFIRMING -> "Change pending — next reply may use either setting."
        Phase.DEFERRED -> "Queued by Hermes — not yet confirmed. Next reply may use either setting."
        Phase.UNCONFIRMED -> "Setting unconfirmed — next reply may use either setting."
    }
}
