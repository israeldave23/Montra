package com.dga.montra.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * What a screen can be showing: nothing yet, data, or a failure.
 *
 * Screens used to launch a coroutine in `onStart`, collect a repository flow, cancel it in `onStop`
 * and toast on `catch` — four pieces of lifecycle bookkeeping per screen, repeated seven times, and
 * every one of them restarted the query on rotation. One state type replaces that: the failure is a
 * value instead of a thrown exception with a toast in the middle of the stream.
 */
internal sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Ready<T>(val value: T) : UiState<T>
    data object Failed : UiState<Nothing>
}

/**
 * Turns a repository flow into screen state, owned by [scope] (a `viewModelScope`).
 *
 * `WhileSubscribed` is what makes the query stop when the screen is not visible: a stopped activity
 * stops collecting, the upstream is cancelled, and returning to the screen re-subscribes and gets a
 * fresh snapshot. The five-second grace period keeps a rotation from re-querying.
 */
internal fun <T> Flow<T>.asUiState(scope: CoroutineScope): StateFlow<UiState<T>> =
    map<T, UiState<T>> { UiState.Ready(it) }
        .catch { emit(UiState.Failed) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

/**
 * A factory for a view model that needs constructor arguments (the repository, an account id).
 *
 * Kept deliberately dull: these view models have no dependencies to inject, and a DI framework for
 * seven classes would be more moving parts than the project has screens.
 */
internal fun <T : ViewModel> viewModelFactory(build: () -> T): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <V : ViewModel> create(modelClass: Class<V>): V = build() as V
    }
