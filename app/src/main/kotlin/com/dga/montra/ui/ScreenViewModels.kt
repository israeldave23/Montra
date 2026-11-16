package com.dga.montra.ui

import androidx.lifecycle.ViewModel
import com.dga.montra.data.RoomFinanceRepository
import com.dga.montra.domain.AccountBalance
import com.dga.montra.domain.BudgetProgress
import com.dga.montra.domain.DashboardState
import com.dga.montra.domain.Goal
import com.dga.montra.domain.MonthlyInsight
import com.dga.montra.domain.Transaction
import com.dga.montra.domain.TransactionFilter
import java.time.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/*
 * One view model per screen that reads the database.
 *
 * Each one owns exactly what the screen used to keep in `onStart`/`onStop`: the query, the month, the
 * filter, the subscription. The activity keeps the view — bindings, dialogs, menus — and renders a
 * state. That split is what makes a rotation stop re-running the query, and what lets the goals
 * screen be split without dragging its observation along.
 */

/** The account list, balances and recent activity on the home screen. */
internal class MainViewModel(private val repository: RoomFinanceRepository) : ViewModel() {

    val dashboard: StateFlow<UiState<DashboardState>> = flow {
        // Names migrated from the old install are renamed before the first read.
        repository.prepareForObservation()
        emitAll(repository.observeDashboard())
    }.asUiState(viewModelScope)
}

/**
 * The activity list. Query and filter are view-model state, so typing survives a rotation and the
 * screen does not hold a "request version" to drop answers from a query the user already replaced.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class TransactionsViewModel(private val repository: RoomFinanceRepository) : ViewModel() {

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(TransactionFilter.ALL)

    private data class Request(val query: String, val filter: TransactionFilter)

    val state: StateFlow<UiState<List<Transaction>>> = flow {
        repository.prepareForObservation()
        val requests = combine(
            query.debounce(QUERY_DEBOUNCE_MS).distinctUntilChanged(),
            filter
        ) { text, selected -> Request(text, selected) }
        emitAll(
            requests.flatMapLatest { request ->
                // Re-query whenever the ledger changes, not only when the text does.
                repository.observeTransactions().map {
                    repository.searchTransactions(request.query)
                        .filter { request.filter.matches(it.type) }
                }
            }
        )
    }.asUiState(viewModelScope)

    fun search(text: String) {
        query.value = text
    }

    fun selectFilter(selected: TransactionFilter) {
        filter.value = selected
    }

    private companion object {
        const val QUERY_DEBOUNCE_MS = 250L
    }
}

/** One month of budgets. The screen shows the current month; the view model owns which. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class BudgetViewModel(private val repository: RoomFinanceRepository) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.now())

    val currentMonth: YearMonth get() = month.value

    val state: StateFlow<UiState<List<BudgetProgress>>> = month
        .flatMapLatest { repository.observeBudgets(it) }
        .asUiState(viewModelScope)

    /** Rolling into a new month while the screen was in the background must not keep the old one. */
    fun refreshMonth() {
        month.value = YearMonth.now()
    }
}

/** Every savings goal, with its pace. */
internal class GoalsViewModel(private val repository: RoomFinanceRepository) : ViewModel() {

    val goals: StateFlow<UiState<List<Goal>>> = repository.observeGoals().asUiState(viewModelScope)
}

/** One month of insights, with the month under the user's control. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class InsightsViewModel(private val repository: RoomFinanceRepository) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.now())

    val currentMonth: YearMonth get() = month.value

    val state: StateFlow<UiState<MonthlyInsight>> = month
        .flatMapLatest { repository.observeInsight(it) }
        .asUiState(viewModelScope)

    /** Returns false when the request would land in the future, which has no insight to show. */
    fun shiftMonth(months: Long): Boolean {
        val target = month.value.plusMonths(months)
        if (target > YearMonth.now()) return false
        month.value = target
        return true
    }
}

/** One account and its own activity, from a single pair of flows. */
internal class AccountDetailViewModel(
    private val repository: RoomFinanceRepository,
    accountId: String
) : ViewModel() {

    val state: StateFlow<UiState<Pair<AccountBalance?, List<Transaction>>>> = combine(
        repository.observeAccount(accountId),
        repository.observeAccountTransactions(accountId)
    ) { balance, transactions -> balance to transactions }
        .asUiState(viewModelScope)
}
