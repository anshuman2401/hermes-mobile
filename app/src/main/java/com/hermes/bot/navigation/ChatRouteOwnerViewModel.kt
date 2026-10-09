package com.hermes.bot.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import com.hermes.bot.feature.chat.ChatViewModel
import kotlin.reflect.KClass

/**
 * BLD-006 S1: activity-retained owner of the keyed ChatViewModel store.
 *
 * Owns at most one child [ChatViewModel] per durable session key; each key gets
 * its own child [ViewModelStore] so that [clear] disposes exactly that key's
 * child (firing its `onCleared()` and cancelling its gateway collectors).
 *
 * Duplicate-collector invariant: the shell calls [clear] for the previous key
 * before a different key is displayed and on route exit, so repeated session
 * switching / navigate away / back / rotation never leaves more than one active
 * gateway event collector for the same transcript, and zero after exit. The
 * owner itself is disposed with its store owner (the Activity): `onCleared()`
 * clears every surviving child store.
 */
class ChatRouteOwnerViewModel(
    private val chatViewModelProvider: ((String) -> ChatViewModel)? = null
) : ViewModel() {

    private val lock = Any()
    private val childStores = HashMap<String, ViewModelStore>()

    /**
     * Number of children disposed via [clear] plus owner teardown (probe
     * observability for tests). Mutation and read are both guarded by [lock]
     * so the count is atomically consistent with the store contents.
     */
    val clearedKeyCount: Int
        get() = synchronized(lock) { clearedKeys.size }

    /** Test-observability ledger of disposed keys (guarded by [lock]). */
    private val clearedKeys = mutableSetOf<String>()

    /**
     * Factory that forwards keyed creation to the existing `chatViewModelProvider`
     * factory. The requested session key is carried in the standard
     * [ViewModelProvider.VIEW_MODEL_KEY] creation extra by the keyed `get` call.
     */
    private val childFactory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
            val sessionKey = requireNotNull(extras[ViewModelProvider.VIEW_MODEL_KEY]) {
                "ChatRouteOwnerViewModel requires keyed ChatViewModel creation"
            }
            val provider = requireNotNull(chatViewModelProvider) {
                "chatViewModelProvider must be supplied to ChatRouteOwnerViewModel"
            }
            @Suppress("UNCHECKED_CAST") // provider returns ChatViewModel by construction; safe for the keyed request
            return provider(sessionKey) as T
        }
    }

    /**
     * Returns the live child for [sessionKey], creating it inside the key's own
     * child store on first access. Never returns two different live instances
     * for the same key.
     */
    fun get(sessionKey: String): ChatViewModel? {
        if (chatViewModelProvider == null) return null
        synchronized(lock) {
            val store = childStores.getOrPut(sessionKey) { ViewModelStore() }
            return ViewModelProvider(store, childFactory).get(sessionKey, ChatViewModel::class.java)
        }
    }

    /**
     * Disposes the child for [sessionKey]: fires the child's `onCleared()` and
     * cancels its gateway collectors. Null or unknown keys are no-ops.
     */
    fun clear(sessionKey: String?) {
        if (sessionKey == null) return
        val store: ViewModelStore? = synchronized(lock) {
            val removed = childStores.remove(sessionKey)
            if (removed != null) {
                clearedKeys.add(sessionKey)
            }
            removed
        }
        store?.clear()
    }

    /**
     * Snapshot of currently alive child keys (probe observability).
     */
    fun activeKeys(): List<String> = synchronized(lock) { childStores.keys.toList() }

    protected override fun onCleared() {
        super.onCleared()
        val stores = synchronized(lock) {
            val all = childStores.values.toList()
            clearedKeys.addAll(childStores.keys)
            childStores.clear()
            all
        }
        stores.forEach { it.clear() }
    }
}
