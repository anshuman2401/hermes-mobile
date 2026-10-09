package io.github.anshuman2401.hermesmobile.navigation

/**
 * Shell-level chat collector cleanup policy, extracted from HermesNavShell's
 * cleanup LaunchedEffect so the disposal decisions are unit-tested
 * (ChatShellCleanupPolicyTest). The effect executes this decision; it holds no
 * policy of its own.
 *
 * Required semantics (BLD-006 S1 repair, review cb52330 HIGH finding):
 * - Clearing a key equal to the currently displayed chat key is FORBIDDEN. On
 *   chat routes, a tracked key equal to the active key (rotation/activity
 *   recreation re-running the effect with restored state, or any same-key
 *   re-run) clears nothing, and the displayed session's collector stays live.
 * - Leaving the Chat routes must still dispose the chat collector (route-exit
 *   acceptance row: zero collectors after exit). The tracked key is nulled on
 *   leaving, so re-entry with the same session creates a fresh VM instead of
 *   the effect resurrecting or wrongly clearing the displayed one.
 * - A session switch (CHAT -> CHAT with a different session) disposes the old
 *   key only; the newly selected key's collector is never touched.
 */
internal object ChatShellCleanupPolicy {

    /**
     * One cleanup step: [clearKey] is the key whose collector must be disposed
     * (null = dispose nothing), [nextTrackedKey] is the key the shell tracks
     * for the next run (null = nothing tracked).
     */
    data class Decision(val clearKey: String?, val nextTrackedKey: String?)

    /**
     * Decides the disposal step for one shell cleanup-effect run.
     *
     * @param currentScreen the screen the shell is displaying after the change
     * @param isExpanded whether the 600dp+ dual-pane layout is active (the
     *   SESSIONS rail hosts a live Chat pane, so it counts as a chat-displaying
     *   screen)
     * @param activeKey the chat session key the shell is displaying (or would
     *   display when it returns to a chat route)
     * @param trackedKey the previously displayed key tracked by the shell
     *   (null = nothing tracked)
     */
    fun decide(
        currentScreen: ShellScreen,
        isExpanded: Boolean,
        activeKey: String,
        trackedKey: String?,
        hasSelection: Boolean = true
    ): Decision {
        val onChatDisplayingRoute =
            hasSelection && (currentScreen == ShellScreen.CHAT ||
                (currentScreen == ShellScreen.SESSIONS && isExpanded))

        return if (onChatDisplayingRoute) {
            // Same-key runs (rotation restore, re-entry effect re-run, first run)
            // must never clear the displayed key. Clear only a genuinely
            // different previously displayed key (session switch).
            val clearKey = trackedKey?.takeIf { it != activeKey }
            Decision(clearKey = clearKey, nextTrackedKey = activeKey)
        } else {
            // Route exit: dispose the last displayed collector and track
            // nothing, so same-session re-entry cannot clear a live VM.
            Decision(clearKey = trackedKey, nextTrackedKey = null)
        }
    }
}

/** Shared shell selection entrypoint; authoritative admission precedes disposal. */
internal suspend fun performWorkspaceSelection(
    name: String,
    repository: io.github.anshuman2401.hermesmobile.data.context.ContextProfileRepository?,
    owner: ChatRouteOwnerViewModel,
    canSwitch: () -> Boolean = { true },
    onAuthoritativeProfile: (io.github.anshuman2401.hermesmobile.data.local.SelectedContextReadResult) -> Unit = {},
    onTransition: (Boolean) -> Unit = {},
    sessions: io.github.anshuman2401.hermesmobile.feature.sessions.SessionsViewModel? = null,
    resetShell: () -> Unit
): io.github.anshuman2401.hermesmobile.data.context.ContextProfileSelectionResult {
    fun unavailable() = io.github.anshuman2401.hermesmobile.data.context.ContextProfileSelectionResult.Error(
        io.github.anshuman2401.hermesmobile.data.context.ContextProfileErrorReason.UNAVAILABLE, "Workspace selection unavailable")
    onTransition(true)
    try {
        fun vetoed() = !canSwitch() || sessions?.uiState?.value?.let {
            it.isCreatingSession || it.activeTurnStreaming.isStreaming
        } == true
        if (repository == null || vetoed()) return unavailable()
        if (!name.matches(Regex("[A-Za-z0-9._-]{1,80}")))
            return io.github.anshuman2401.hermesmobile.data.context.ContextProfileSelectionResult.Error(
                io.github.anshuman2401.hermesmobile.data.context.ContextProfileErrorReason.MALFORMED, "Invalid profile name format")
        val actual = repository.readSelectedProfileAuthoritative()
        if (actual !is io.github.anshuman2401.hermesmobile.data.local.SelectedContextReadResult.Selected &&
            actual != io.github.anshuman2401.hermesmobile.data.local.SelectedContextReadResult.Absent) return unavailable()
        if (vetoed()) return unavailable()
        if ((actual as? io.github.anshuman2401.hermesmobile.data.local.SelectedContextReadResult.Selected)?.name == name)
            return io.github.anshuman2401.hermesmobile.data.context.ContextProfileSelectionResult.Success(name)
        resetWorkspaceOwnership(owner, resetShell)
        val result = repository.selectProfile(name)
        onAuthoritativeProfile(repository.readSelectedProfileAuthoritative())
        return result
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (_: Exception) {
        onAuthoritativeProfile(io.github.anshuman2401.hermesmobile.data.local.SelectedContextReadResult.Failed)
        return unavailable()
    } finally { onTransition(false) }
}

internal fun resetWorkspaceOwnership(owner: ChatRouteOwnerViewModel, resetShell: () -> Unit) {
    owner.activeKeys().forEach(owner::clear)
    resetShell()
}

/** Original request owner and a newly stamped UI continuation serve different purposes. */
internal suspend fun performWorkspaceRecovery(
    request: io.github.anshuman2401.hermesmobile.data.session.WorkspaceRecoveryRequest,
    generation: Long,
    repository: io.github.anshuman2401.hermesmobile.data.context.ContextProfileRepository?,
    owner: ChatRouteOwnerViewModel,
    sessions: io.github.anshuman2401.hermesmobile.feature.sessions.SessionsViewModel,
    resetShell: () -> Unit,
    onTransition: (Boolean) -> Unit = {}
): io.github.anshuman2401.hermesmobile.data.session.WorkspaceRecoveryApplyResult {
    onTransition(true)
    try {
        if (!sessions.isWorkspaceIntentCurrent(generation) || sessions.uiState.value.isCreatingSession || repository == null)
            return io.github.anshuman2401.hermesmobile.data.session.WorkspaceRecoveryApplyResult.Stale
        resetWorkspaceOwnership(owner, resetShell)
        val continuation = sessions.captureWorkspaceIntent()
        val applied = sessions.applyWorkspaceRecovery(request, continuation)
        val actual = repository.readSelectedProfileAuthoritative()
        if (!sessions.isWorkspaceIntentCurrent(continuation)) return io.github.anshuman2401.hermesmobile.data.session.WorkspaceRecoveryApplyResult.Stale
        if (applied == io.github.anshuman2401.hermesmobile.data.session.WorkspaceRecoveryApplyResult.Applied &&
            actual == io.github.anshuman2401.hermesmobile.data.local.SelectedContextReadResult.Absent) {
            sessions.loadRecoveredWorkspace(continuation)
        } else sessions.setNetworkError("Workspace recovery unavailable")
        return applied
    } finally { onTransition(false) }
}

/** The same production factory gate is used by both layouts and the title effect. */
internal fun selectedChatForDisplay(
    owner: ChatRouteOwnerViewModel,
    hasSelection: Boolean,
    screen: ShellScreen,
    expanded: Boolean,
    chatsTab: Boolean,
    key: String
): io.github.anshuman2401.hermesmobile.feature.chat.ChatViewModel? =
    if (hasSelection && (screen == ShellScreen.CHAT || (screen == ShellScreen.SESSIONS && expanded && chatsTab)))
        owner.get(key) else null

/** Captured composition intent also guards delayed success and preview callbacks. */
internal fun workspaceActionIsCurrent(expected: Long, current: Long, allowed: Boolean): Boolean =
    expected == current && allowed

internal fun workspaceOpenCallback(actionsAllowed: () -> Boolean, onOpen: (String) -> Unit): (String) -> Unit = { id ->
    if (actionsAllowed()) onOpen(id)
}

/** Shared by the expanded detail and CHAT route's create controls. */
internal fun shellCreateAction(
    sessionsViewModel: io.github.anshuman2401.hermesmobile.feature.sessions.SessionsViewModel?,
    workspaceActionsAllowed: () -> Boolean,
    openSelectedChat: (String) -> Unit
): () -> Unit = {
    if (workspaceActionsAllowed()) {
        if (sessionsViewModel == null) openSelectedChat("new-session")
        else sessionsViewModel.createNewChat { id -> openSelectedChat(id) }
    }
}
