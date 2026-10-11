package com.hermes.bot.feature.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Presentation-only source contracts for the chat layout request.
 *
 * These assertions supplement the existing behavior tests because Compose
 * semantics require separate instrumentation execution.
 */
class ChatUiPresentationSourceContractTest {
    @Test fun storedOlderIntentUsesAvailabilityAndViewportAnchor() {
        val screen = source("com/hermes/bot/feature/chat/ChatScreen.kt")
        assertTrue(screen.contains("uiState?.storedHistoryEarlierAvailable == false"))
        assertTrue(screen.contains("OlderHistoryAnchor(tailRevision, olderRevision"))
        assertTrue(screen.contains("listState.layoutInfo.visibleItemsInfo.map"))
        assertTrue(screen.contains("displayEntriesWithDays.map { it.key }"))
        assertTrue(screen.contains("listOf(\"history-begin-indicator\")"))
        assertTrue(screen.contains("TimelineFollowPolicy.olderAnchorTarget(anchor, currentDisplayKeys"))
        assertTrue(screen.contains("listState.scrollToItem(index, offset)"))
        assertTrue(screen.contains("onTimelineChanged(followingLatest, timeline.isNotEmpty(), olderRevision)"))
        assertTrue(screen.contains("listState.isScrollInProgress"))
        assertTrue(screen.indexOf("olderAnchor = OlderHistoryAnchor") < screen.indexOf("viewModel.loadEarlier()"))
    }


    private fun source(relativePath: String): String {
        val direct = File("src/main/java/$relativePath")
        val fromRepoRoot = File("app/src/main/java/$relativePath")
        val file = if (direct.exists()) direct else fromRepoRoot
        assertTrue("Expected source file to exist: $relativePath", file.exists())
        return file.readText()
    }

    @Test
    fun composer_hasSafeAttachmentAffordanceBeforeField_andSeparateSendControl() {
        val composer = source("com/hermes/bot/feature/chat/components/ChatComposer.kt")
        val attachment = composer.indexOf("Icons.Default.Add")
        val field = composer.indexOf("OutlinedTextField(")
        val send = composer.indexOf("PaperPlaneIcon(", field)

        assertTrue("attachment icon must be present", attachment >= 0)
        assertTrue("attachment icon must be before the text field", attachment < field)
        assertTrue("send icon must remain after the text field", field < send)
        assertTrue(
            "attachment callback must default to null",
            composer.contains("onAttachmentClick: (() -> Unit)? = null")
        )
        assertTrue(
            "attachment affordance must explain its unavailable default",
            composer.contains("Add attachment (not available yet)")
        )

        val fieldRegion = composer.substring(field, composer.indexOf("val buttonEnabled", field))
        // Approved dictation adds one embedded microphone; attachment and Send stay separate.
        assertEquals("only the microphone is embedded", 1, Regex("IconButton\\(").findAll(fieldRegion).count())
        assertTrue("embedded control is microphone Stop/start", fieldRegion.contains("onStopDictation else onDictationClick"))
        assertTrue("microphone has a distinct accessible label", fieldRegion.contains("Dictate message"))
        assertFalse("attachment stays outside the field", fieldRegion.contains("onAttachmentClick"))
        assertFalse("Send stays outside the field", fieldRegion.contains("else onSend"))
        assertTrue(
            "stop-streaming behavior must remain",
            composer.contains("onClick = if (showStop) onStopStreaming else onSend")
        )
    }

    @Test
    fun attachmentChooser_opensSheetFromComposerWithCameraAndPickerCallbacks() {
        val composer = source("com/hermes/bot/feature/chat/components/ChatComposer.kt")
        val screen = source("com/hermes/bot/feature/chat/ChatScreen.kt")
        val menu = source("com/hermes/bot/feature/chat/components/AttachmentSheet.kt")
        val anchor = composer.substring(composer.indexOf("Box {\n                IconButton("), composer.indexOf("val fieldShape"))

        assertTrue(anchor.contains("onClick = { onAttachmentClick?.invoke() }"))
        assertTrue(anchor.contains("attachmentMenu()"))
        assertTrue(screen.contains("attachmentMenu = {"))
        assertTrue(screen.contains("AttachmentMenu("))
        assertTrue(screen.contains("onDismiss = { showAttachmentMenu = false }"))
        assertTrue(screen.contains("photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))"))
        assertTrue(screen.contains("documentPicker.launch(DOCUMENT_MIME_TYPES)"))
        assertTrue(menu.contains("ModalBottomSheet("))
        assertTrue(menu.contains("AttachmentAction(\"Camera\"") && menu.contains("AttachmentAction(\"Photos\"") && menu.contains("AttachmentAction(\"Documents\""))
        assertTrue(screen.contains("ActivityResultContracts.TakePicture()"))
        assertTrue(screen.contains("viewModel?.startCamera()"))
        assertTrue(screen.contains("viewModel?.useCameraPhoto()"))
        assertFalse(menu.contains("DropdownMenu("))
    }

    @Test
    fun composer_stopButtonAccessibilityLabel_isStopGenerating() {
        // S5 polish wave (feature-chat-stop-plan-v1): the streaming-state
        // composer button must be announced as "Stop generating" (Hermes Go
        // reference wording), not the old "Stop streaming". Compose semantics
        // are not JVM-assertable without instrumentation, so this is
        // the source-text contract, per this file's stated convention.
        val composer = source("com/hermes/bot/feature/chat/components/ChatComposer.kt")
        assertTrue(
            "streaming stop button must be labelled \"Stop generating\"",
            composer.contains("showStop -> \"Stop generating\"")
        )
        assertFalse(
            "stale \"Stop streaming\" label must not return",
            composer.contains("\"Stop streaming\"")
        )
        // Send/idle labels stay untouched by the polish wave.
        assertTrue(
            "idle send label must remain",
            composer.contains("else -> \"Send prompt\"")
        )
    }

    @Test
    fun composer_streamingActionIsSolidSquareInsideCenteredCompactCircle() {
        val composer = source("com/hermes/bot/feature/chat/components/ChatComposer.kt")
        val action = composer.substring(composer.indexOf("if (edit == null) IconButton("))
        val visualStart = action.indexOf("Box(")
        assertTrue("action must contain a visual Box", visualStart >= 0)
        val parent = action.substring(0, visualStart)
        val visual = action.substring(visualStart, action.indexOf("if (isSubmitting && !queueEnabled) {"))
        val stop = action.substring(action.indexOf("} else if (showStop) {"), action.indexOf("PaperPlaneIcon("))

        assertTrue("interactive parent stays 48dp", parent.contains(".size(48.dp)"))
        assertTrue("callback stays on parent", parent.contains("onClick = if (showStop) onStopStreaming else onSend"))
        assertTrue("enabled stays on parent", parent.contains("enabled = buttonEnabled"))
        assertTrue("semantics stay on parent", parent.contains(".semantics {"))
        assertFalse("parent must not paint the resting circle", parent.contains(".background(") || parent.contains(".clip("))
        assertTrue("visual is a 44dp circle", visual.contains(".size(44.dp)") && visual.contains(".clip(CircleShape)"))
        assertTrue("glyphs are centered in visual", visual.contains("contentAlignment = Alignment.Center"))
        assertTrue("streaming fill retains error role", visual.contains("if (showStop) MaterialTheme.colorScheme.error"))
        assertTrue("send fill retains primary role", visual.contains("else if (buttonEnabled) MaterialTheme.colorScheme.primary"))
        assertTrue("disabled fill retained", visual.contains("else MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)"))
        assertFalse("visual adds no click or semantics ownership", visual.contains("onClick") || visual.contains(".clickable") || visual.contains(".semantics") || visual.contains("contentDescription"))
        assertTrue("stop is a solid 10dp foreground Box", stop.contains("Box(") && stop.contains(".size(10.dp)") && stop.contains(".background(MaterialTheme.colorScheme.onError)"))
        assertFalse("stop square is not rounded or alpha-reduced", stop.contains(".clip(") || stop.contains("alpha") || stop.contains("RoundedCornerShape"))
        assertFalse("stop has no duplicate description", stop.contains("contentDescription"))
        assertFalse("broken vector must be removed", composer.contains("Icons.Default.Stop") || composer.contains("Icons.Filled.Stop") || composer.contains("ImageVector") || composer.contains("PathParser") || composer.contains("SolidColor"))
    }

    @Test
    fun userBubble_isTrailingWithLeadingMessageBodyAndOnlySentStatusTrailing() {
        val userBubble = source("com/hermes/bot/feature/chat/components/UserMessageBubble.kt")
        val assistantBubble = source("com/hermes/bot/feature/chat/components/AssistantMessageBubble.kt")
        val messageBody = userBubble.substring(
            userBubble.indexOf("text = invocationText"),
            userBubble.indexOf("// Status indication")
        )
        val deliveryStatus = userBubble.substring(
            userBubble.indexOf("// Status indication"),
            userBubble.indexOf("// CHAT-009")
        )

        assertTrue("outer user-bubble column must place its shell at the trailing edge", userBubble.contains("horizontalAlignment = Alignment.End"))
        assertTrue("bubble body must keep its content at the leading edge", userBubble.contains("horizontalAlignment = Alignment.Start"))
        assertTrue("message body must align to the leading edge", messageBody.contains("textAlign = TextAlign.Start"))
        assertFalse("message body must not align to the trailing edge", messageBody.contains("textAlign = TextAlign.End"))
        assertTrue("Sent status must align to the trailing edge", deliveryStatus.contains("UserMessageState.SENT -> Alignment.End"))
        assertTrue(
            "Sending and unknown statuses must stay at the leading edge",
            deliveryStatus.contains("UserMessageState.SENDING, UserMessageState.UNKNOWN -> Alignment.Start")
        )
        assertTrue(
            "delivery-status row must apply its state-specific alignment",
            deliveryStatus.contains("modifier = Modifier.align(statusAlignment)")
        )
        assertTrue("timestamp caption must remain", userBubble.contains("timestampMillis = message.timestamp"))
        assertTrue(
            "trailing bubble corner must be the compact corner",
            userBubble.contains("RoundedCornerShape(topStart = HermesShapeTokens.bubbleCorner, topEnd = 4.dp, bottomStart = HermesShapeTokens.bubbleCorner, bottomEnd = HermesShapeTokens.bubbleCorner)")
        )
        assertTrue("user bubble must retain its 80% width cap", userBubble.contains(".fillMaxWidth(0.8f)"))
        assertTrue("user bubble must retain its primary container fill", userBubble.contains("color = MaterialTheme.colorScheme.primaryContainer"))
        assertTrue("UNKNOWN handling must remain", userBubble.contains("UserMessageState.UNKNOWN"))
        assertTrue("check-history recovery action must remain", userBubble.contains("Check history"))
        assertTrue("retry recovery action must remain", userBubble.contains("Send as new prompt"))
        assertFalse("assistant bubble must remain unboxed", assistantBubble.contains("Surface("))
    }

    @Test
    fun userBubble_sendAsNewPromptButton_isDisabledWhileManualRefreshInFlight() {
        // CHAT-019-PROD-003 (repair round 2, defense-in-depth layer): the
        // UNKNOWN-banner retry affordance must follow the same refresh-gated
        // enablement convention as the refresh notice's Retry control and the
        // composer readiness flag. UserMessageBubble owns the disabled
        // rendering via an enablement parameter; ChatScreen computes it from
        // the manual-refresh lane so the button cannot invoke the ViewModel
        // retry path mid-refresh. 'Check history' is untouched by this gate.
        val userBubble = source("com/hermes/bot/feature/chat/components/UserMessageBubble.kt")
        val chatScreen = source("com/hermes/bot/feature/chat/ChatScreen.kt")

        assertTrue(
            "UserMessageBubble must accept a retry enablement flag",
            userBubble.contains("retryEnabled: Boolean = true")
        )
        val bannerBlock = userBubble.substring(
            userBubble.indexOf("if (onRetryAsNewPrompt != null)"),
            userBubble.indexOf("\"Send as new prompt\"")
        )
        assertTrue(
            "retry OutlinedButton must honor the enablement flag",
            bannerBlock.contains("enabled = retryEnabled")
        )
        // The gate is the standard M3 disabled affordance, exactly like the
        // refresh notice's Retry TextButton — no bespoke disabled styling.
        assertFalse(
            "retry button must not hand-roll custom disabled styling",
            bannerBlock.contains("alpha = 0.")
        )

        assertTrue(
            "ChatScreen must drive the retry enablement from !isRefreshing",
            chatScreen.contains("retryEnabled = !isRefreshing")
        )
        // 'Check history' stays unconditionally wired (explicitly out of scope).
        assertTrue(
            "ChatScreen must keep 'Check history' wired outside the refresh gate",
            chatScreen.contains("onCheckHistory = { viewModel?.loadSession() }")
        )
    }

    @Test
    fun assistantBubble_hasNoOuterCardSurface_butKeepsBodyAndTerminalPresentation() {
        val assistantBubble = source("com/hermes/bot/feature/chat/components/AssistantMessageBubble.kt")

        assertFalse("assistant bubble must not render an outer Surface", assistantBubble.contains("Surface("))
        assertFalse("assistant bubble must not use the card surface token", assistantBubble.contains("surfaceContainer"))
        assertTrue("Markdown rendering must remain", assistantBubble.contains("MarkdownRenderer("))
        assertTrue("selection must remain", assistantBubble.contains("SelectionContainer"))
        assertTrue("copy semantics must remain", assistantBubble.contains("Copy response"))
        assertTrue("terminal status chips must remain", assistantBubble.contains("Turn failed"))
        assertTrue("timestamp rendering must remain", assistantBubble.contains("ChatTimestampCaption"))
    }

    @Test
    fun flatChatSurfaces_normalFillsMatchCanvasBackground_whileRetainingCardBordersAndExclusions() {
        val userBubble = source("com/hermes/bot/feature/chat/components/UserMessageBubble.kt")
        val toolCard = source("com/hermes/bot/feature/chat/components/ToolActivityCard.kt")
        val blockCard = source("com/hermes/bot/feature/chat/components/ToolActivityBlockCard.kt")
        val gatewayCard = source("com/hermes/bot/feature/chat/components/GenericGatewayCard.kt")
        val reasoningCard = source("com/hermes/bot/feature/chat/components/ReasoningCard.kt")

        // 1. User bubbles retain their distinct semantic fill; activity fills match canvas.
        assertTrue("UserMessageBubble must use primaryContainer fill", userBubble.contains("color = MaterialTheme.colorScheme.primaryContainer"))
        assertFalse("UserMessageBubble must not use the canvas fill", userBubble.contains("color = MaterialTheme.colorScheme.background"))

        assertTrue("ToolActivityCard surface must use background", toolCard.contains("color = MaterialTheme.colorScheme.background"))
        assertFalse("ToolActivityCard must not use surfaceContainerHigh fill", toolCard.contains("color = MaterialTheme.colorScheme.surfaceContainerHigh"))

        assertTrue("ToolActivityBlockCard surface must use background", blockCard.contains("color = MaterialTheme.colorScheme.background"))
        assertFalse("ToolActivityBlockCard must not use surfaceContainerHigh fill", blockCard.contains("color = MaterialTheme.colorScheme.surfaceContainerHigh"))

        assertTrue("GenericGatewayCard surface must use background", gatewayCard.contains("color = MaterialTheme.colorScheme.background"))
        assertFalse("GenericGatewayCard must not use surfaceContainer fill", gatewayCard.contains("color = MaterialTheme.colorScheme.surfaceContainer"))

        assertTrue("ReasoningCard surface must use background", reasoningCard.contains("color = MaterialTheme.colorScheme.background"))
        assertFalse("ReasoningCard must not use surfaceContainer fill", reasoningCard.contains("color = MaterialTheme.colorScheme.surfaceContainer"))

        // 2. Existing card borders must be retained on expandable activity cards
        val expectedBorder = ".border(1.dp, HermesTheme.extendedColors.glassOutline, RoundedCornerShape(HermesShapeTokens.cardCornerSmall))"
        assertTrue("ToolActivityCard must retain border", toolCard.contains(expectedBorder))
        assertTrue("ToolActivityBlockCard must retain border", blockCard.contains(expectedBorder))
        assertTrue("GenericGatewayCard must retain border", gatewayCard.contains(expectedBorder))
        assertTrue("ReasoningCard must retain border", reasoningCard.contains(expectedBorder))

        // 3. Exclusions: chips and explicit decision banners are not flattened
        assertTrue("ToolActivityCard duration chip must keep surfaceContainerHighest", toolCard.contains("MaterialTheme.colorScheme.surfaceContainerHighest"))
        assertTrue("ToolActivityBlockCard must retain full-list header affordance", blockCard.contains("text = \"View all\""))
        assertTrue("ToolActivityBlockCard must retain full-list header callback", blockCard.contains(".clickable(onClick = onHeaderClick)"))
        assertTrue("ToolActivityBlockCard must retain exact-step callback", blockCard.contains("onStepClick(step.id)"))
        assertFalse("ToolActivityBlockCard must not show a redundant overflow strip", blockCard.contains("earlier\""))
        assertTrue("UserMessageBubble UNKNOWN decision banner must keep errorContainer", userBubble.contains("MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)"))

        // Composer frosted glass shell REMOVED (owner decision 2026-09-26,
        // CHAT-012): the composer sits directly on the chat background; the
        // input field keeps its own half-weight outline. The old pin required
        // .glassSurface(GlassDefaults.style()) — inverted here.
        val composer = source("com/hermes/bot/feature/chat/components/ChatComposer.kt")
        assertFalse("ChatComposer must not render the frosted-glass surface (owner decision: boundary removed)", composer.contains(".glassSurface(GlassDefaults.style())"))
        assertFalse("ChatComposer must not import glassSurface", composer.contains("import com.hermes.bot.design.glassSurface"))
    }

    @Test
    fun composer_fieldGeometryAndOutline_meetHalfWeightAndTwelveDpRequirements() {
        val composer = source("com/hermes/bot/feature/chat/components/ChatComposer.kt")

        // Explicit 24.dp rounded field shape
        assertTrue("composer must define 24.dp field shape", composer.contains("val fieldShape = RoundedCornerShape(24.dp)"))
        assertTrue("OutlinedTextField must use fieldShape", composer.contains("shape = fieldShape"))

        // Native OutlinedTextField retained (not a custom input reimplementation)
        assertTrue("OutlinedTextField must remain", composer.contains("OutlinedTextField("))
        assertTrue("maxLines = 4 must remain", composer.contains("maxLines = 4"))
        // Owner directive 2026-09-27 (ui-enter-newline-v1): Enter inserts a
        // newline; send ONLY via the button — ImeAction.Send must be GONE.
        assertFalse("IME Send must be gone from the composer", composer.contains("ImeAction.Send"))
        assertTrue("semantics contentDescription must remain", composer.contains("contentDescription = \"Message input. Current value:"))

        // Half-weight outline: exactly 0.5.dp unfocused and 1.dp focused
        assertTrue("outline width must be 1.dp focused and 0.5.dp unfocused", composer.contains("val outlineWidth = if (isFocused) 1.dp else 0.5.dp"))
        assertTrue("OutlinedTextField must have border with outlineWidth and fieldShape", composer.contains("width = outlineWidth"))
        assertTrue("OutlinedTextField must have border with fieldShape", composer.contains("shape = fieldShape"))

        // Native border colors transparent so half-weight border renders without double outline
        assertTrue("native border colors must be transparent", composer.contains("focusedBorderColor = Color.Transparent"))
        assertTrue("native border colors must be transparent", composer.contains("unfocusedBorderColor = Color.Transparent"))

        // Focus interaction tracking
        assertTrue("interactionSource must track focus", composer.contains("interactionSource.collectIsFocusedAsState()"))
    }

    @Test
    fun composer_placeholder_usesBodyMediumTypography() {
        val composer = source("com/hermes/bot/feature/chat/components/ChatComposer.kt")

        assertTrue("placeholder must use Ask Hermes...", composer.contains("text = \"Ask Hermes...\""))
        assertTrue("placeholder must use bodyMedium typography", composer.contains("style = MaterialTheme.typography.bodyMedium"))
    }

    @Test
    fun composer_compactGlyphsPreserveTargetsReadinessAndSubmissionPrecedence() {
        val composer = source("com/hermes/bot/feature/chat/components/ChatComposer.kt")
        val addButtonStart = composer.lastIndexOf("IconButton(", composer.indexOf("onClick = { onAttachmentClick?.invoke() }"))
        val attachment = composer.substring(addButtonStart, composer.indexOf("val fieldShape"))
        val attachmentParent = attachment.substring(0, attachment.indexOf("Icon("))
        val addGlyph = attachment.substring(attachment.indexOf("Icon("))
        val action = composer.substring(composer.indexOf("if (edit == null) IconButton("))
        val send = action.substring(action.indexOf("PaperPlaneIcon("))
        val spinner = action.substring(action.indexOf("if (isSubmitting && !queueEnabled) {"), action.indexOf("} else if (showStop) {"))
        val readiness = composer.substring(composer.indexOf("val canSend"), composer.indexOf("Column("))
        val field = composer.substring(composer.indexOf("OutlinedTextField("), composer.indexOf("val buttonEnabled"))

        assertTrue("attachment parent keeps 48dp allocation", attachmentParent.contains(".size(48.dp)"))
        assertTrue("plus is 16dp", addGlyph.contains(".size(16.dp)"))
        assertFalse("attachment remains backgroundless", attachment.contains(".background("))
        assertTrue("outlined send is 20dp", send.contains(".size(20.dp)"))
        assertTrue("send tint preserves readiness", send.contains("tint = if (canSend) MaterialTheme.colorScheme.onPrimary") && send.contains("else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)"))
        assertTrue("attachment callback preserved", attachmentParent.contains("onClick = { onAttachmentClick?.invoke() }"))
        assertTrue("attachment disabled state preserved", attachmentParent.contains("enabled = onAttachmentClick != null"))
        assertTrue("streaming still enables Stop even while submitting", composer.contains("val buttonEnabled = if (showStop) true else canSend"))
        assertTrue("only slash commands replace Stop with Send", composer.contains("val showStop = isStreaming && !isSlashCommand"))
        assertTrue("submitting spinner precedes streaming square", spinner.contains("CircularProgressIndicator(") && spinner.contains("Modifier.size(20.dp)") && spinner.contains("strokeWidth = 2.dp") && spinner.contains("color = MaterialTheme.colorScheme.onPrimary"))
        assertTrue("explicit readiness stays authoritative", readiness.contains("val canSend = isComposerReady ?: (") && readiness.contains("draftText.isNotBlank() || pendingImages.isNotEmpty()") && readiness.contains("!isSubmitting &&") && readiness.contains("!isStreaming &&") && readiness.contains("!isSendBlocked"))
        // Owner directive 2026-09-27 (ui-enter-newline-v1): Enter = newline,
        // button-only send — the IME action is unconditional Default (never
        // readiness-guarded Send), and no onSend callback is wired to the field.
        assertTrue("IME action must be unconditional Default", field.contains("keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)"))
        assertFalse("IME action must never be Send", field.contains("ImeAction.Send"))
        assertFalse("field must not wire an onSend keyboard callback", field.contains("KeyboardActions"))
        assertTrue("draft editing retained", field.contains("value = fieldValue") && field.contains("onValueChange = { fieldValue = it; onDraftChange(it.text) }"))
        assertTrue("submitting label retained", action.contains("isSubmitting -> \"Sending prompt...\""))
        assertTrue("blocked label retained", action.contains("isSendBlocked -> \"Send prompt. Session needs to be restored.\""))
    }

    @Test
    fun timestampCaption_rendersHalfDpDividerWhenTimestampPresent_andNothingWhenAbsent() {
        val timestampSource = source("com/hermes/bot/feature/chat/components/ChatTimestampCaption.kt")

        // Absent timestamp sentinel renders nothing
        assertTrue("absent timestamp must early return", timestampSource.contains("if (timestampMillis <= 0L) return"))

        // When real timestamp present, renders full-width 0.5.dp divider beneath caption
        assertTrue("divider must be full width", timestampSource.contains(".fillMaxWidth()"))
        assertTrue("divider must be 0.5.dp", timestampSource.contains("thickness = 0.5.dp"))
        assertTrue("divider must use outlineVariant role", timestampSource.contains("color = MaterialTheme.colorScheme.outlineVariant"))
        assertTrue("time text has exactly 3.dp top padding", timestampSource.contains("modifier = Modifier.padding(top = 3.dp)"))

        // Formatting/localization preserved
        assertTrue("ChatDaySeparatorPolicy.timeCaption must remain", timestampSource.contains("ChatDaySeparatorPolicy.timeCaption(timestampMillis, zone)"))
        assertTrue("ZoneId.systemDefault must remain", timestampSource.contains("ZoneId.systemDefault()"))
    }

    @Test
    fun userEditIcon_precedesTimestampAndRetainsAccessibleAction() {
        val bubble = source("com/hermes/bot/feature/chat/components/UserMessageBubble.kt")
        val caption = source("com/hermes/bot/feature/chat/components/ChatTimestampCaption.kt")

        assertTrue("edit action must lead the time", bubble.contains("leadingContent = onEdit?.let"))
        assertTrue("leading action must render before time text", caption.indexOf("leadingContent?.invoke()") < caption.indexOf("text = caption"))
        assertTrue("edit must remain available when the source time is absent", bubble.contains("message.timestamp <= 0L && onEdit != null"))
        assertTrue("edit button must retain a 48dp touch target", bubble.contains("IconButton(onClick = onClick, modifier = Modifier.size(48.dp))"))
        assertTrue("icon must have an accessible label", bubble.contains("contentDescription = \"Edit message\""))
        assertFalse("standalone Edit text button must be removed", bubble.contains("Text(\"Edit\")"))
    }

    @Test
    fun composer_noAttachmentBehaviorWired() {
        val composer = source("com/hermes/bot/feature/chat/components/ChatComposer.kt")

        // Forbidden attachment behavior checks
        assertFalse("must not reference Intent", composer.contains("Intent"))
        assertFalse("must not reference ActivityResult", composer.contains("ActivityResult"))
        assertFalse("must not reference Uri", composer.contains("Uri"))
        assertFalse("must not reference rememberLauncherForActivityResult", composer.contains("rememberLauncherForActivityResult"))
        assertFalse("must not wire picker or upload", composer.contains("upload") || composer.contains("picker"))
    }

    // ------------------------------------------------------------------
    // CHAT-018 PR-A source contracts (plan §A4 + frozen test matrix)
    // ------------------------------------------------------------------

    @Test
    fun chat018_blockCard_headerDelegatesToHeaderTitle_notRawActionCount() {
        val blockCard = source("com/hermes/bot/feature/chat/components/ToolActivityBlockCard.kt")

        // OQ-001: notes-only cards must read "Agent notes" via the policy
        // function — the card never concatenates formatActionCount directly.
        assertTrue(
            "ToolActivityBlockCard header must call ToolBlockPolicy.headerTitle(block)",
            blockCard.contains("ToolBlockPolicy.headerTitle(block)")
        )
        assertFalse(
            "ToolActivityBlockCard must not compute the header copy via formatActionCount",
            blockCard.contains("ToolBlockPolicy.formatActionCount")
        )
    }

    @Test
    fun chat018_policy_noteCountExclusion_andHeaderLiterals_areByteExact() {
        val policy = source("com/hermes/bot/feature/chat/ToolBlockPolicy.kt")

        // AC-5: notes are excluded from the action count at the source level.
        assertTrue(
            "partition must gate totalActionCount with steps.count non-note (notes excluded)",
            policy.contains("totalActionCount = steps.count { it !is ToolBlockStep.Note }")
        )
        // OQ-001 literal: capital-A "Agent notes" — never a lowercase variant.
        assertTrue(
            "headerTitle literal must be exactly \"Agent notes\"",
            policy.contains("\"Agent notes\"")
        )
        assertFalse(
            "lowercase-token ban (chat016 learning): no lowercase 'agent notes' literal",
            policy.contains("\"agent notes\"")
        )
        // REQ-005: note timestamps are excluded from the aggregate elapsed window.
        assertTrue(
            "validTimestamps must filter out note rows",
            policy.contains(".filter { it !is AgentNoteEntry }")
        )
        // Partition admits notes into the activity card grouping.
        assertTrue(
            "activity grouping must admit AgentNoteEntry rows",
            policy.contains("is GenericGatewayActivity, is AgentNoteEntry -> {")
        )
    }

    @Test
    fun chat018_stepRow_noteBranch_usesAgentNotePrefixWithQuietStyling() {
        val blockCard = source("com/hermes/bot/feature/chat/components/ToolActivityBlockCard.kt")

        assertTrue(
            "StepRow must render a 'Agent note' prefix for ToolBlockStep.Note",
            blockCard.contains("\"Agent note\"")
        )
        assertTrue(
            "note prefix must use labelMedium",
            blockCard.contains("labelMedium")
        )
        assertTrue(
            "note body must use bodySmall",
            blockCard.contains("bodySmall")
        )
        assertTrue(
            "note body must be quiet (alpha 0.6)",
            blockCard.contains("0.6f")
        )
        // Notes never render tool styling (duration chip / tool icon).
        val noteBranch = blockCard.substring(
            blockCard.indexOf("if (step is ToolBlockStep.Note)"),
            blockCard.indexOf("} else {", blockCard.indexOf("if (step is ToolBlockStep.Note)"))
        )
        assertFalse(
            "note branch must not render a duration text",
            noteBranch.contains("step.durationText")
        )
        assertFalse(
            "note branch must not render the tool Build icon",
            noteBranch.contains("Icons.Default.Build")
        )
    }

    @Test
    fun chat018_viewModel_interimRouting_neverTouchesReplyBubble() {
        val viewModel = source("com/hermes/bot/feature/chat/ChatViewModel.kt")

        // A2: already-streamed interims are a full no-op.
        assertTrue(
            "applyInterimNote routing must no-op alreadyStreamed events",
            viewModel.contains("event.alreadyStreamed == true -> Unit")
        )
        // Notes append to the live turn; they never feed applyMessageDelta.
        assertTrue(
            "active-turn interims must append AgentNoteEntry rows",
            viewModel.contains("AgentNoteEntry(")
        )
        // D-01, owner-locked drop (plan ui-remove-unattributed-activity-notices-v1):
        // unattributable interim/delta activity renders nothing — the
        // ViewModel must no longer emit "Unattributed assistant activity",
        // and the no-active-turn interim branch must not route to
        // addGenericActivity. Other generic notices (message.terminal,
        // reasoning, Unknown events) are out of scope and keep their call
        // sites.
        assertFalse(
            "unattributed interim/delta generic notice must be removed",
            viewModel.contains("\"Unattributed assistant activity\"")
        )
        // RED-first (plan ui-drop-unattributed-notices-v2-plan.md, PR #178
        // precedent): the remaining two "Unattributed …" notices are also
        // owner-locked drops — reasoning/thinking deltas with no attributable
        // active turn and message.terminal events with no active turn buffer
        // render nothing. The ViewModel must not emit their summaries and
        // both no-active-turn branches must not route to addGenericActivity.
        assertFalse(
            "unattributed reasoning generic notice must be removed",
            viewModel.contains("\"Unattributed reasoning activity\"")
        )
        assertFalse(
            "unattributed turn generic notice must be removed",
            viewModel.contains("\"Unattributed turn activity\"")
        )
        val reasoningBranch = viewModel.substring(
            viewModel.indexOf("private fun applyReasoningDelta"),
            viewModel.indexOf("val displayKind = when (kind)")
        )
        assertFalse(
            "no-active-turn reasoning branch must not route to addGenericActivity",
            reasoningBranch.contains("addGenericActivity(")
        )
        val terminalBranch = viewModel.substring(
            viewModel.indexOf("private fun finalizeAssistantTurn"),
            viewModel.indexOf("deltaPublicationScheduler?.flush()", viewModel.indexOf("private fun finalizeAssistantTurn"))
        )
        assertFalse(
            "null-entry terminal branch must not route to addGenericActivity",
            terminalBranch.contains("addGenericActivity(")
        )
        val interimBranch = viewModel.substring(
            viewModel.indexOf("is GatewayEvent.MessageInterim"),
            viewModel.indexOf("is GatewayEvent.MessageComplete", viewModel.indexOf("is GatewayEvent.MessageInterim"))
        )
        assertFalse(
            "no-active-turn interim branch must not route to addGenericActivity",
            interimBranch.contains("addGenericActivity(")
        )
    }

    @Test
    fun chat018_timelineFollowPolicy_fingerprintsNoteRows() {
        val follow = source("com/hermes/bot/feature/chat/TimelineFollowPolicy.kt")

        assertTrue(
            "contentSignature must fingerprint AgentNoteEntry rows",
            follow.contains("is AgentNoteEntry -> \"an:")
        )
    }

    @Test
    fun chat018_orphanNoteRow_rendersQuietNoteNotBubble() {
        val chatScreen = source("com/hermes/bot/feature/chat/ChatScreen.kt")

        assertTrue(
            "ChatScreen must render orphan AgentNoteEntry rows",
            chatScreen.contains("is AgentNoteEntry ->")
        )
        assertTrue(
            "orphan note render must use the note styling",
            chatScreen.contains("color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)")
        )
    }

    @Test
    fun chat018_sheet_noteDetailView_andIconBranch_present() {
        val sheet = source("com/hermes/bot/feature/chat/components/ActivityBottomSheet.kt")

        assertTrue(
            "sheet must route ToolBlockStep.Note to NoteDetailView",
            sheet.contains("is ToolBlockStep.Note -> {")
        )
        assertTrue(
            "sheet must define NoteDetailView",
            sheet.contains("private fun NoteDetailView(")
        )
    }

    @Test
    fun chat018_copyAll_noteStep_usesAgentNoteLabel() {
        val detail = source("com/hermes/bot/feature/chat/ToolDetailPolicy.kt")

        assertTrue(
            "formatCopyAll must label note steps 'Agent note'",
            detail.contains("\"Agent note\"")
        )
        assertTrue(
            "formatCopyAll must emit the full note text for note steps",
            detail.contains("appendLine(step.note.text)")
        )
    }

    @Test
    fun chat019_refreshControl_andInlineFailureContracts_arePresent() {
        val chatScreen = source("com/hermes/bot/feature/chat/ChatScreen.kt")
        val viewModel = source("com/hermes/bot/feature/chat/ChatViewModel.kt")

        assertTrue("refresh icon import must be standard", chatScreen.contains("icons.filled.Refresh"))
        assertTrue("refresh semantics label must be present", chatScreen.contains("contentDescription = \"Refresh chat\""))
        assertTrue("refresh target must be at least 48dp", chatScreen.contains("defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)"))
        assertTrue("refresh must be gated for new sessions", chatScreen.contains("if (sessionId != \"new-session\")"))
        assertTrue("refresh must be disabled during active work", chatScreen.contains("enabled = !isRefreshing && !isSubmitting && !isStreaming"))
        assertTrue("refresh must use the same slot for its spinner", chatScreen.contains("if (isRefreshing)"))
        assertFalse("context access moved out of header options", chatScreen.contains("contentDescription = \"Chat options\""))
        val composer = source("com/hermes/bot/feature/chat/components/ChatComposer.kt")
        val context = composer.indexOf("ContextUsageIcon(")
        val yolo = composer.indexOf("YoloToggle(")
        assertTrue("context control must be immediately before YOLO", context >= 0 && yolo > context)
        assertTrue("context action must be supplied by the screen", chatScreen.contains("onOpenContextUsage = if"))
        assertTrue("inline retry label must be present", chatScreen.contains("Text(\"Retry\")"))
        assertTrue("inline dismiss label must be present", chatScreen.contains("Text(\"Dismiss\")"))
        assertTrue("retry must call refreshSession", chatScreen.contains("onRetry = { viewModel?.refreshSession() }"))
        assertTrue("dismiss must call dismissRefreshError", chatScreen.contains("onDismiss = { viewModel?.dismissRefreshError() }"))
        assertTrue("refresh must not reuse initial hydration", viewModel.contains("fun refreshSession()") && !viewModel.substring(viewModel.indexOf("fun refreshSession()"), viewModel.indexOf("fun dismissRefreshError()")).contains("loadSession()"))
    }

    @Test
    fun chat019_refreshState_isSeparateFromInitialLoadingAndGeneralError() {
        val uiState = source("com/hermes/bot/feature/chat/ChatUiState.kt")
        assertTrue("manual refresh busy state must be explicit", uiState.contains("val isRefreshing: Boolean = false"))
        assertTrue("manual refresh error state must be explicit", uiState.contains("val refreshErrorMessage: String? = null"))
    }
}
