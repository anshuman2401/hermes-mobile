package com.hermes.bot.feature.chat.files

import com.hermes.bot.data.files.*
import com.hermes.bot.feature.chat.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ConversationFilesState(
    val items: List<ConversationFile> = emptyList(),
    val byMessage: Map<String, List<ConversationFile>> = emptyMap(),
    val visible: Boolean = false,
    val selected: ConversationFile? = null,
    val loading: Boolean = false,
    val content: FileContent? = null,
    val message: String? = null,
    val online: Boolean = false,
    val limited: Boolean = false
)
/** Owns presentation requests and revokes late results; credentials remain behind the repository. */
class ConversationFilesController(
    private val scope: CoroutineScope,
    private val repository: ConversationFileRepository,
    private val parsingContext: kotlin.coroutines.CoroutineContext = Dispatchers.Default
) {
    private val mutable = MutableStateFlow(ConversationFilesState())
    val state: StateFlow<ConversationFilesState> = mutable.asStateFlow()
    private var binding: Pair<String, String>? = null
    private var listOwner: FileOwner? = null
    private var parseCache: Map<String, Pair<String, List<ConversationFile>>> = emptyMap()
    private var generation = 0L
    private var owner: FileOwner? = null
    private var download: Job? = null
    private var monitor: Job? = null

    suspend fun update(stored: String?, runtime: String?, online: Boolean, timeline: List<TimelineEntry>) {
        val next = if (stored != null && runtime != null) stored to runtime else null
        if (next != binding || !online) {
            clearPreview(); binding = next; listOwner = null; parseCache = emptyMap()
            mutable.update { it.copy(items = emptyList(), byMessage = emptyMap()) }
        }
        val captured = binding
        mutable.update { it.copy(online = online && next != null) }
        val previousCache = parseCache
        val nextCache = LinkedHashMap<String, Pair<String, List<ConversationFile>>>()
        val projection = withContext(parsingContext) {
            val all = LinkedHashMap<String, ConversationFile>()
            val rows = LinkedHashMap<String, List<ConversationFile>>()
            var chars = 0L
            var limited = false
            for (entry in timeline) {
                val refs = when(entry) {
                    is AssistantMessage -> {
                        if (entry.state != AssistantMessageState.COMPLETE || entry.sourceOverflow) continue
                        chars += entry.text.length
                        if (chars > FileLimits.WINDOW_CHARS) { limited = true; break }
                        if (entry.text.length > FileLimits.MESSAGE_CHARS) limited = true
                        val refs = previousCache[entry.id]?.takeIf { it.first === entry.text }?.second
                            ?: ConversationFileReferences.assistant(entry.text)
                        nextCache[entry.id] = entry.text to refs
                        refs
                    }
                    is ToolActivity -> entry.files
                    else -> emptyList()
                }
                val accepted = refs.mapNotNull { f ->
                    all[f.key] ?: if (all.size >= FileLimits.REFERENCES) { limited = true; null }
                    else f.also { all[f.key] = it }
                }.distinctBy { it.key }
                if (accepted.isNotEmpty()) rows[entry.id] = accepted
            }
            Triple(all.values.toList(), rows.toMap(), limited)
        }
        currentCoroutineContext().ensureActive()
        if (binding != captured) return
        if (online && next != null && projection.first.isNotEmpty() && listOwner == null) {
            val authority = try { repository.capture(next.first, next.second) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            currentCoroutineContext().ensureActive()
            if (binding != captured) return
            listOwner = authority
        }
        parseCache = nextCache
        if (mutable.value.selected?.let { selected -> projection.first.none { it.key == selected.key } } == true) clearPreview()
        mutable.update { it.copy(items = projection.first, byMessage = projection.second, limited = projection.third) }
    }
    fun show() { mutable.update { it.copy(visible = true, message = null) } }
    fun back() {
        val preview = mutable.value.selected != null
        clearPreview()
        mutable.update { it.copy(visible = preview) }
    }
    fun close() { clearPreview(); mutable.update { it.copy(visible = false) } }
    fun open(reference: ConversationFile) {
        val item = mutable.value.items.find { it.key == reference.key } ?: return
        clearPreview()
        mutable.update { it.copy(visible = true, selected = item) }
        val identity = binding
        if (!mutable.value.online || identity == null) {
            mutable.update { it.copy(message = "Reconnect to open files from Hermes.") }; return
        }
        val token = generation
        mutable.update { it.copy(loading = true) }
        download = scope.launch {
            var fetched: FileContent? = null
            try {
                val captured = listOwner ?: throw ConversationFileException(FileFailure.STALE)
                if (!repository.isCurrent(captured)) throw ConversationFileException(FileFailure.STALE)
                if (token != generation) return@launch
                owner = captured
                monitor = scope.launch {
                    val started = System.nanoTime()
                    while (isActive) {
                        delay(500)
                        val current = try { repository.isCurrent(captured) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
                        if (token != generation) return@launch
                        if (!current || (System.nanoTime() - started) > 300_000_000_000L) {
                            clearPreview(); mutable.update { it.copy(message = FileFailure.STALE.message) }; return@launch
                        }
                    }
                }
                fetched = repository.download(captured, item)
                if (token != generation || !repository.isCurrent(captured)) { fetched.discard(); return@launch }
                mutable.update { it.copy(loading = false, content = fetched, message = null) }
            } catch (cancelled: CancellationException) { fetched?.discard(); throw cancelled }
            catch (failure: Exception) {
                fetched?.discard()
                if (token == generation) mutable.update { it.copy(loading = false, message = (failure as? ConversationFileException)?.failure?.message ?: FileFailure.NETWORK.message) }
            }
        }
    }
    /** The exact visible content must still own the export after the Android picker returns. */
    suspend fun export(content: FileContent, action: suspend (FileContent) -> Unit): Boolean {
        val captured = owner ?: return false
        val token = generation
        if (mutable.value.content !== content || !repository.isCurrent(captured) || token != generation) return false
        // Independent short-lived copy: cancellation/invalidation can zero preview without corrupting an authorized export.
        val copy = FileContent(content.reference, content.bytes.copyOf())
        return try { action(copy); true } finally { copy.discard() }
    }
    fun background() {
        // A system Save/share chooser may stop the Activity. Keep already downloaded bytes for
        // its exact-owner callback; loading and all stale/session-changed work still cancel.
        if (mutable.value.loading) {
            val selected = mutable.value.selected
            clearPreview()
            mutable.update { it.copy(selected = selected, message = "Download paused. Tap Retry to continue.") }
        }
    }
    fun clearPreview() {
        generation++
        download?.cancel(); download = null
        monitor?.cancel(); monitor = null
        owner = null
        mutable.value.content?.discard()
        mutable.update { it.copy(selected = null, loading = false, content = null, message = null) }
    }
}
