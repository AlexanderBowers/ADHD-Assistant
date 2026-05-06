package com.example.adhdassistant.ui.chores

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.adhdassistant.ADHDApplication
import com.example.adhdassistant.R
import com.example.adhdassistant.config.ChoreItem
import com.example.adhdassistant.databinding.ActivityChoreListBinding
import com.example.adhdassistant.databinding.ItemChoreBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Collections

class ChoreListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChoreListBinding
    private val configRepository get() = (application as ADHDApplication).configRepository

    private lateinit var choreAdapter: ChoreAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChoreListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.intentions_screen_title)

        applyWindowInsets()
        setupRecyclerView()
        setupFab()
        observeChores()
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.choreRecyclerView.setPadding(0, 0, 0,
                bars.bottom + resources.getDimensionPixelSize(R.dimen.fab_bottom_clearance))
            val fabParams = binding.fabAddChore.layoutParams as ViewGroup.MarginLayoutParams
            fabParams.bottomMargin = bars.bottom + resources.getDimensionPixelOffset(R.dimen.fab_margin)
            binding.fabAddChore.layoutParams = fabParams
            insets
        }
    }

    private fun setupRecyclerView() {
        choreAdapter = ChoreAdapter(
            onDeleteClick = { chore -> confirmDelete(chore) },
            onDragStart   = { holder -> itemTouchHelper.startDrag(holder) }
        )
        binding.choreRecyclerView.adapter = choreAdapter

        val touchCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.START
        ) {
            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                choreAdapter.moveItem(from.adapterPosition, to.adapterPosition)
                return true
            }
            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {
                val pos = holder.adapterPosition
                val removed = choreAdapter.removeAt(pos) ?: return
                persistList()
                showUndoSnackbar(pos, removed)
            }
            override fun clearView(rv: RecyclerView, holder: RecyclerView.ViewHolder) {
                super.clearView(rv, holder)
                persistList()
            }
        }
        itemTouchHelper = ItemTouchHelper(touchCallback)
        itemTouchHelper.attachToRecyclerView(binding.choreRecyclerView)
    }

    private fun setupFab() {
        binding.fabAddChore.setOnClickListener { showAddDialog() }
    }

    private fun showAddDialog() {
        val inputLayout = TextInputLayout(this).apply {
            setPadding(resources.getDimensionPixelOffset(R.dimen.dialog_input_margin), 8,
                       resources.getDimensionPixelOffset(R.dimen.dialog_input_margin), 0)
        }
        val input = TextInputEditText(this).apply {
            hint = getString(R.string.intentions_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 2
        }
        inputLayout.addView(input)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.intentions_screen_title)
            .setView(inputLayout)
            .setPositiveButton(R.string.intentions_add, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.setOnShowListener {
            val okBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            okBtn.isEnabled = false
            input.doAfterTextChanged { text -> okBtn.isEnabled = !text.isNullOrBlank() }
            okBtn.setOnClickListener {
                val text = input.text?.toString()?.trim()
                if (!text.isNullOrEmpty()) {
                    lifecycleScope.launch { configRepository.addChore(text) }
                    dialog.dismiss()
                }
            }
            input.requestFocus()
        }
        dialog.show()
    }

    private fun confirmDelete(chore: ChoreItem) {
        val pos = choreAdapter.indexOf(chore)
        val removed = choreAdapter.removeAt(pos) ?: return
        persistList()
        showUndoSnackbar(pos, removed)
    }

    private fun showUndoSnackbar(pos: Int, removed: ChoreItem) {
        Snackbar.make(binding.root, R.string.intentions_undo_remove, Snackbar.LENGTH_LONG)
            .setAction(android.R.string.cancel) {
                choreAdapter.insertAt(pos, removed)
                persistList()
            }.show()
    }

    private fun observeChores() {
        lifecycleScope.launch {
            configRepository.choreListFlow.collectLatest { chores ->
                choreAdapter.submitList(chores)
                binding.emptyChoresText.visibility = if (chores.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun persistList() {
        lifecycleScope.launch { configRepository.setChoreList(choreAdapter.currentItems()) }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    inner class ChoreAdapter(
        private val onDeleteClick: (ChoreItem) -> Unit,
        private val onDragStart:   (RecyclerView.ViewHolder) -> Unit
    ) : RecyclerView.Adapter<ChoreAdapter.ChoreViewHolder>() {

        private val items = mutableListOf<ChoreItem>()

        fun submitList(newList: List<ChoreItem>) {
            items.clear(); items.addAll(newList); notifyDataSetChanged()
        }
        fun currentItems(): List<ChoreItem> = items.toList()
        fun indexOf(chore: ChoreItem) = items.indexOfFirst { it.id == chore.id }

        fun moveItem(from: Int, to: Int) {
            if (from < 0 || to < 0 || from >= items.size || to >= items.size) return
            Collections.swap(items, from, to); notifyItemMoved(from, to)
        }
        fun removeAt(pos: Int): ChoreItem? {
            if (pos < 0 || pos >= items.size) return null
            val removed = items.removeAt(pos); notifyItemRemoved(pos); return removed
        }
        fun insertAt(pos: Int, item: ChoreItem) {
            val p = pos.coerceIn(0, items.size); items.add(p, item); notifyItemInserted(p)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ChoreViewHolder(ItemChoreBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: ChoreViewHolder, position: Int) = holder.bind(items[position])
        override fun getItemCount() = items.size

        inner class ChoreViewHolder(private val b: ItemChoreBinding) : RecyclerView.ViewHolder(b.root) {
            @Suppress("ClickableViewAccessibility")
            fun bind(chore: ChoreItem) {
                b.choreNameText.text = chore.text
                b.btnDeleteChore.setOnClickListener { onDeleteClick(chore) }
                b.imgDragHandle.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) onDragStart(this)
                    false
                }
            }
        }
    }
}
