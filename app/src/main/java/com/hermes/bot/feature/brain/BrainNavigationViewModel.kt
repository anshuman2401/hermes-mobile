package com.hermes.bot.feature.brain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.hermes.bot.data.local.SelectedContextReadResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class BrainDestination { OVERVIEW, MODELS, AUXILIARY_MODELS, SKILLS }
enum class BrainBackAction { OVERVIEW, SETTINGS }

/** Visit-local presentation owner. Only the displayed page has a live child store. */
class BrainNavigationViewModel(
    readSelectedProfile: suspend () -> SelectedContextReadResult
) : ViewModel() {
    private val _destination = MutableStateFlow(BrainDestination.OVERVIEW)
    val destination = _destination.asStateFlow()
    private val _profileCaption = MutableStateFlow("Loading")
    val profileCaption = _profileCaption.asStateFlow()
    private var childStore = ViewModelStore()
    private var modelsProvider: (() -> BrainMainSettingsViewModel)? = null
    private var auxiliaryProvider: (() -> BrainAuxiliaryModelsViewModel)? = null
    private var skillsProvider: (() -> BrainSkillsViewModel)? = null

    init {
        viewModelScope.launch {
            val result = try {
                readSelectedProfile()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SelectedContextReadResult.Failed
            }
            currentCoroutineContext().ensureActive()
            _profileCaption.value = when (result) {
                is SelectedContextReadResult.Selected -> "Profile: ${result.name}"
                SelectedContextReadResult.Absent -> "Not selected"
                SelectedContextReadResult.Unreadable, SelectedContextReadResult.Failed -> "Profile unavailable"
            }
        }
    }

    fun updateProviders(
        models: (() -> BrainMainSettingsViewModel)?,
        auxiliary: (() -> BrainAuxiliaryModelsViewModel)?,
        skills: (() -> BrainSkillsViewModel)?
    ) {
        if (modelsProvider !== models || auxiliaryProvider !== auxiliary || skillsProvider !== skills) {
            clearChild()
            modelsProvider = models
            auxiliaryProvider = auxiliary
            skillsProvider = skills
        }
    }

    fun open(destination: BrainDestination) {
        if (_destination.value != destination) {
            clearChild()
            _destination.value = destination
        }
    }

    /** Both toolbar and system Back consume this same transition. Detail Back stays in Skills. */
    fun back(): BrainBackAction = if (_destination.value == BrainDestination.OVERVIEW) {
        BrainBackAction.SETTINGS
    } else {
        open(BrainDestination.OVERVIEW)
        BrainBackAction.OVERVIEW
    }

    fun models(): BrainMainSettingsViewModel? = child(BrainDestination.MODELS, modelsProvider, BrainMainSettingsViewModel::class.java)
    fun auxiliary(): BrainAuxiliaryModelsViewModel? = child(BrainDestination.AUXILIARY_MODELS, auxiliaryProvider, BrainAuxiliaryModelsViewModel::class.java)
    fun skills(): BrainSkillsViewModel? = child(BrainDestination.SKILLS, skillsProvider, BrainSkillsViewModel::class.java)

    private fun <T : ViewModel> child(destination: BrainDestination, creator: (() -> T)?, type: Class<T>): T? {
        if (_destination.value != destination || creator == null) return null
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = creator() as V
        }
        return ViewModelProvider(childStore, factory).get(destination.name, type)
    }

    private fun clearChild() {
        childStore.clear()
        childStore = ViewModelStore()
    }

    override fun onCleared() {
        childStore.clear()
        super.onCleared()
    }
}

/** Shared ordinary detail return: never reloads or replaces the Skills list owner. */
internal fun brainDetailReturn(detailStore: ViewModelStore, clearIdentity: () -> Unit): () -> Unit = {
    detailStore.clear()
    clearIdentity()
}

internal fun brainNavigationOwner(
    store: ViewModelStore,
    readSelectedProfile: suspend () -> SelectedContextReadResult
): BrainNavigationViewModel = ViewModelProvider(store, object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = BrainNavigationViewModel(readSelectedProfile) as T
}).get(BrainNavigationViewModel::class.java)
