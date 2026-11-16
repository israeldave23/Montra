package com.dga.montra

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dga.montra.data.RoomFinanceRepository
import com.dga.montra.databinding.ActivityGoalsBinding
import com.dga.montra.ui.GoalsViewModel
import com.dga.montra.ui.UiState
import com.dga.montra.ui.viewModelFactory
import com.dga.montra.domain.Goal
import com.dga.montra.domain.GoalOrdering
import com.dga.montra.domain.GoalSummary
import com.dga.montra.domain.Money
import com.google.android.material.snackbar.Snackbar
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Savings goals: what you are putting money aside for, how far along you are, whether you are on
 * pace for the date you picked, and the quickest path to updating the tracked amount.
 *
 * Goals never move money: all copy says so, and the only writes are to the goal row.
 */
class GoalsActivity : SectionActivity(3) {
    private lateinit var binding: ActivityGoalsBinding

    internal val repo by lazy { RoomFinanceRepository.get(this) }

    private lateinit var list: RecyclerView
    private lateinit var emptyView: View
    private lateinit var summaryCard: View
    private lateinit var summarySaved: TextView
    private lateinit var summaryDetail: TextView
    private lateinit var actionButton: Button
    private lateinit var adapter: GoalAdapter

    private var goals: List<Goal> = emptyList()
    private val viewModel: GoalsViewModel by viewModels { viewModelFactory { GoalsViewModel(repo) } }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        binding = ActivityGoalsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.goalTitle.setText(R.string.goals_title)
        binding.goalSubtitle.setText(R.string.goals_subtitle)

        list = binding.goalList
        emptyView = binding.goalEmpty
        summaryCard = binding.goalSummary
        summarySaved = binding.goalSummaryAmount
        summaryDetail = binding.goalSummaryDetail
        actionButton = binding.goalAction

        list.layoutManager = LinearLayoutManager(this)
        adapter = GoalAdapter(
            onOpen = { goal -> showGoalDetail(goal) },
            onMenu = { goal, anchor -> showGoalMenu(goal, anchor) }
        )
        list.adapter = adapter
        actionButton.setOnClickListener { showGoalEditor(null) }
        bindBottomNavigation(binding.root, tab)
        render(emptyList())
        collectState()
    }

    /**
     * Renders what the view model holds. Collection stops when the screen is not started, so nothing
     * queries the database behind a screen the user cannot see.
     */
    private fun collectState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.goals.collect { state ->
                    when (state) {
                        is UiState.Ready -> render(state.value)
                        UiState.Failed -> snackbar(R.string.storage_error)
                        UiState.Loading -> Unit
                    }
                }
            }
        }
    }

    private fun render(items: List<Goal>) {
        goals = GoalOrdering.sorted(items, LocalDate.now(), ZoneId.systemDefault())
        adapter.submitList(goals)
        val empty = goals.isEmpty()
        list.visibility = if (empty) View.GONE else View.VISIBLE
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        summaryCard.visibility = if (empty) View.GONE else View.VISIBLE
        actionButton.setText(if (empty) R.string.goal_create_first else R.string.new_goal)
        if (!empty) renderSummary(goals)
    }

    private fun renderSummary(items: List<Goal>) {
        val summary = GoalSummary.of(items)
        summarySaved.text = summary.savedByCurrency.entries.joinToString("  ·  ") { (currency, minor) ->
            Money(minor, currency).format()
        }
        val completed = getString(R.string.goal_summary_completed, summary.completedCount, summary.goalCount)
        val remaining = summary.remainingByCurrency.entries
            .filter { it.value > 0L }
            .joinToString("  ·  ") { (currency, minor) ->
                getString(R.string.goal_remaining_amount, Money(minor, currency).format())
            }
        summaryDetail.text = if (remaining.isEmpty()) completed
        else getString(R.string.goal_summary_detail, completed, remaining)
    }

    internal fun defaultCurrency(): String {
        val preferred = settings.defaultCurrency
        return if (preferred != null && preferred in Money.currencies) preferred else Money.currencies.first()
    }

    internal fun snackbar(message: Int) = snackbar(getString(message))

    internal fun snackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    internal fun snackbarWithUndo(message: String, undo: () -> Unit) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) { undo() }
            .show()
    }
}
