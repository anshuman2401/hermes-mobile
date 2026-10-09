package com.hermes.bot.feature.botscreen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.bot.data.bots.BotTarget
import com.hermes.bot.data.botscreen.BotScreenRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class BotScreenViewModel(private val repository: BotScreenRepository) : ViewModel() {
    val state: StateFlow<com.hermes.bot.data.botscreen.BotScreenState> = repository.state
    private var poll: Job? = null
    private var events: Job? = null
    private var operation: Job? = null

    fun enter(target: BotTarget, profile: String) {
        poll?.cancel(); events?.cancel(); operation?.cancel()
        operation = viewModelScope.launch {
            repository.enter(target, profile)
            poll = viewModelScope.launch {
                while (isActive) { delay(2_000); repository.refresh() }
            }
            events = viewModelScope.launch { repository.changes.collect { repository.refresh() } }
        }
    }
    fun start() { operation = viewModelScope.launch { repository.start() } }
    fun reconnect() { operation = viewModelScope.launch { repository.reconnect() } }
    fun takeOver() { operation = viewModelScope.launch { repository.takeOver() } }
    fun handBack() { operation = viewModelScope.launch { repository.handBack() } }
    fun pointer(x: Int, y: Int, buttons: Int) = repository.pointer(x, y, buttons)
    fun scroll(down: Boolean) = repository.scroll(down)
    fun type(text: String) {
        text.codePoints().limit(1024).forEach { codepoint ->
            val keysym = when (codepoint) {
                10 -> 0xff0d
                9 -> 0xff09
                else -> if (codepoint <= 255) codepoint else 0x01000000 or codepoint
            }
            repository.key(keysym)
        }
    }
    fun key(keysym: Int) = repository.key(keysym)
    fun leave() {
        poll?.cancel(); events?.cancel(); operation?.cancel()
        operation = viewModelScope.launch { repository.leave() }
    }
    override fun onCleared() { repository.closeImmediately(); super.onCleared() }
}
