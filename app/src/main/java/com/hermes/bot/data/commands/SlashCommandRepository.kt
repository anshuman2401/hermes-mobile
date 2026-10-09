package com.hermes.bot.data.commands

import com.hermes.bot.data.gateway.*
import com.hermes.bot.data.session.SessionActivityOwner
import com.hermes.bot.data.session.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CommandSession internal constructor(
    val storedId: String, val runtimeId: String, internal val owner: SessionActivityOwner? = null
) { override fun toString() = "CommandSession([redacted])" }
sealed interface CommandOutcome {
    class Result(val directive: SlashDirective) : CommandOutcome
    class Navigate(val destination: SlashNavigation, val query: String = "") : CommandOutcome
    class Failure(val message: String, val outcomeUnknown: Boolean = false) : CommandOutcome
}
interface SlashCommandRepository {
    suspend fun capture(storedId: String, runtimeId: String): CommandSession?
    suspend fun isCurrent(session: CommandSession): Boolean
    suspend fun suggestions(session: CommandSession, query: String): List<SlashSuggestion>
    suspend fun execute(session: CommandSession, invocation: SlashInvocation): CommandOutcome
}

class DefaultSlashCommandRepository(
    private val adapter: GatewayProtocolAdapter,
    private val sessions: SessionRepository,
    private val clock: () -> Long = System::currentTimeMillis
) : SlashCommandRepository {
    private val mutex = Mutex()
    private var cachedSession: CommandSession? = null
    private var cachedCatalog: SlashCatalog? = null
    private var cachedAt = 0L
    override suspend fun capture(storedId: String, runtimeId: String): CommandSession? =
        sessions.captureSendActivityOwner(storedId, runtimeId)?.let { CommandSession(storedId, runtimeId, it) }
    override suspend fun isCurrent(session: CommandSession): Boolean =
        session.owner?.let { sessions.isCommandOwnerCurrent(it) } == true
    private suspend fun catalog(session: CommandSession): SlashCatalog = mutex.withLock {
        check(isCurrent(session))
        val previous = cachedSession
        val cached = cachedCatalog
        if (cached != null && previous?.storedId == session.storedId && previous.runtimeId == session.runtimeId &&
            isCurrent(previous) && clock() - cachedAt in 0..60_000L) return@withLock cached
        val owner = checkNotNull(session.owner)
        val result = adapter.slashCatalog(session.runtimeId, com.hermes.bot.data.origin.CanonicalOrigin.parse(owner.authority.lease.origin).getOrThrow(), owner.scope.gatewayGeneration)
        check(isCurrent(session))
        val value = (result as? GatewayResult.Success)?.value ?: error("Catalog unavailable")
        cachedSession = session; cachedCatalog = value; cachedAt = clock()
        value
    }
    private fun canonical(catalog: SlashCatalog, name: String): String =
        SlashCommandPolicy.aliases[name] ?: catalog.aliases[name] ?: name
    private fun offered(catalog: SlashCatalog, text: String): Boolean {
        val token = text.takeWhile { !it.isWhitespace() }.lowercase(java.util.Locale.ROOT)
        val name = canonical(catalog, token)
        return name !in SlashCommandPolicy.unsupported &&
            (name in SlashCommandPolicy.local.map { it.text } || name !in catalog.unavailable)
    }
    override suspend fun suggestions(session: CommandSession, query: String): List<SlashSuggestion> {
        if (SlashCommandPolicy.query(query) == null || !isCurrent(session)) return emptyList()
        if (query.startsWith("/reasoning ")) {
            return SESSION_REASONING_LEVELS.filter { it.startsWith(query.substringAfter(' ').trim()) }
                .map { SlashSuggestion("/reasoning $it", if (it == "none") "Off" else it.replaceFirstChar(Char::uppercase), "Options") }
        }
        val cat = catalog(session)
        if (query == "/") {
            val locals = SlashCommandPolicy.local
            val names = locals.map { it.text }.toSet()
            return (locals + cat.suggestions.filter {
                offered(cat, it.text) && it.text !in names && canonical(cat, it.text) == it.text
            }).take(SlashCommandPolicy.MAX_ROWS)
        }
        val owner = checkNotNull(session.owner)
        val result = adapter.slashComplete(session.runtimeId, query, com.hermes.bot.data.origin.CanonicalOrigin.parse(owner.authority.lease.origin).getOrThrow(), owner.scope.gatewayGeneration)
        check(isCurrent(session))
        val value = (result as? GatewayResult.Success)?.value ?: error("Completions unavailable")
        val arg = value.replaceFrom > 1
        if (value.replaceFrom > query.length) error("Invalid replacement")
        val remote = value.items.map {
            val full = if (arg) query.take(value.replaceFrom) + it.text else if (it.text.startsWith("/")) it.text else "/"+it.text
            SlashSuggestion(full, it.description, if (arg) "Options" else it.group)
        }.filter { offered(cat, it.text) }
        val local = if (arg || query.any { it.isWhitespace() }) emptyList() else SlashCommandPolicy.local.filter {
            it.text.startsWith(query.lowercase(java.util.Locale.ROOT)) && remote.none { row -> row.text == it.text }
        }
        return (local + remote).sortedBy { if (it.group == "Skills") 1 else 0 }.take(SlashCommandPolicy.MAX_ROWS)
    }
    override suspend fun execute(session: CommandSession, invocation: SlashInvocation): CommandOutcome {
        if (!isCurrent(session)) return CommandOutcome.Failure("Session changed. Restore the chat before running a command.")
        if (invocation.name == "yolo" && invocation.argument !in setOf("on", "off"))
            return CommandOutcome.Failure("Use /yolo, /yolo on or /yolo off.")
        return try {
            // Native commands remain usable even when catalog discovery is unavailable.
            val cat = if ("/"+invocation.name in SlashCommandPolicy.local.map { it.text } ||
                "/"+invocation.name in SlashCommandPolicy.aliases) SlashCatalog(emptyList()) else catalog(session)
            var command = invocation
            val visited = mutableSetOf<String>()
            repeat(8) {
                val name = canonical(cat, "/"+command.name)
                command = SlashInvocation(name.removePrefix("/"), command.argument)
                if (!visited.add(command.name)) return CommandOutcome.Failure("Command alias loop detected.")
                if (!offered(cat, command.text)) return CommandOutcome.Failure("This command is not available in the Android app.")
                if (!isCurrent(session)) return CommandOutcome.Failure("Session changed. Command cancelled.")
                when (command.name) {
                    "new" -> return CommandOutcome.Navigate(SlashNavigation.NEW_CHAT)
                    "resume" -> return CommandOutcome.Navigate(SlashNavigation.SESSIONS, command.argument)
                    "model" -> {
                        if (command.argument.isBlank()) return CommandOutcome.Navigate(SlashNavigation.MODEL)
                        val options = sessions.listSessionModelOptions(session.runtimeId)
                        if (!isCurrent(session)) return CommandOutcome.Failure("Session changed. Command cancelled.")
                        val matches = (options as? GatewayResult.Success)?.value.orEmpty().filter {
                            it.model.equals(command.argument, true) || (it.providerSlug+"/"+it.model).equals(command.argument, true)
                        }
                        if (matches.size != 1) return CommandOutcome.Failure("Choose a model from the model picker.")
                        return picked(sessions.setSessionModel(session.runtimeId, matches.single()), session)
                    }
                    "reasoning" -> {
                        if (command.argument.isBlank()) return CommandOutcome.Navigate(SlashNavigation.MODEL)
                        if (command.argument !in SESSION_REASONING_LEVELS) return CommandOutcome.Failure("Choose a supported reasoning level.")
                        return picked(sessions.setSessionReasoning(session.runtimeId, command.argument), session)
                    }
                    "help" -> {
                        val available = catalog(session)
                        val text = (SlashCommandPolicy.local + available.suggestions.filter { offered(available, it.text) })
                            .distinctBy { it.text }.joinToString("\n") { it.text+" — "+it.description }.take(16_384)
                        return CommandOutcome.Result(SlashDirective.Output(text))
                    }
                }
                val owner = checkNotNull(session.owner)
                val response = adapter.slashExecute(session.runtimeId, command, com.hermes.bot.data.origin.CanonicalOrigin.parse(owner.authority.lease.origin).getOrThrow(), owner.scope.gatewayGeneration)
                if (!isCurrent(session)) return CommandOutcome.Failure("Session changed. Check history before trying again.")
                if (response is GatewayResult.Failure) {
                    val unknown = response.failure is GatewayFailure.Transport || response.failure is GatewayFailure.SubmissionUnknown ||
                        (command.name in setOf("compress", "yolo") && response.failure is GatewayFailure.NotConnected)
                    return CommandOutcome.Failure(
                        if (unknown) "Command outcome unknown. Check the chat before trying again."
                        else "Hermes could not run this command.", outcomeUnknown = unknown)
                }
                val directive = (response as GatewayResult.Success).value
                if (directive is SlashDirective.Alias) {
                    val alias = SlashCommandPolicy.parse("/"+directive.target.removePrefix("/")+
                        if (command.argument.isBlank()) "" else " "+command.argument)
                        ?: return CommandOutcome.Failure("Invalid command alias.")
                    command = alias
                } else return CommandOutcome.Result(directive)
            }
            CommandOutcome.Failure("Too many command aliases.")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { CommandOutcome.Failure("Slash commands unavailable. Restore the chat and try again.", outcomeUnknown = true) }
    }
    private suspend fun picked(result: GatewayResult<SessionPickerSetResult>, session: CommandSession): CommandOutcome {
        if (!isCurrent(session)) return CommandOutcome.Failure("Session changed. Check the chat before trying again.")
        val value = (result as? GatewayResult.Success)?.value ?: return CommandOutcome.Failure("Hermes could not change the session setting.")
        return CommandOutcome.Result(SlashDirective.Output(when {
            value.confirmRequired -> "Use the model picker to confirm this change."
            value.deferred -> "Setting change queued on Hermes."
            else -> "Session setting updated."
        }))
    }
}
