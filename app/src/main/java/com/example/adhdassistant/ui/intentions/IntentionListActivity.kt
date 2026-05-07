package com.example.adhdassistant.ui.intentions

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
import com.example.adhdassistant.config.IntentionItem
import com.example.adhdassistant.databinding.ActivityIntentionListBinding
import com.example.adhdassistant.databinding.ItemIntentionBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Collections

class IntentionListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIntentionListBinding
    private val configRepository get() = (application as ADHDApplication).configRepository
    private lateinit var intentionAdapter: IntentionAdapter
    private var itemTouchHelper: ItemTouchHelper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIntentionListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.intentionRecyclerView.setPadding(0, 0, 0, bars.bottom + 200)
            insets
        }

        intentionAdapter = IntentionAdapter(
            onCompleteClick = { item -> completeIntention(item) },
            onDeleteClick = { item -> confirmDelete(item) },
            onDragStart = { viewHolder -> itemTouchHelper?.startDrag(viewHolder) }
        )
        binding.intentionRecyclerView.adapter = intentionAdapter

        setupDragAndDrop()

        binding.fabAddIntention.setOnClickListener { showAddIntentionDialog() }

        lifecycleScope.launch {
            configRepository.intentionListFlow.collectLatest { list ->
                intentionAdapter.items.clear()
                intentionAdapter.items.addAll(list)
                intentionAdapter.notifyDataSetChanged()
                binding.emptyIntentionsText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun setupDragAndDrop() {
        val callback = object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(r: RecyclerView, src: RecyclerView.ViewHolder, tgt: RecyclerView.ViewHolder): Boolean {
                intentionAdapter.moveItem(src.bindingAdapterPosition, tgt.bindingAdapterPosition)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
            override fun clearView(r: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(r, vh)
                saveList()
            }
        }
        itemTouchHelper = ItemTouchHelper(callback).apply { attachToRecyclerView(binding.intentionRecyclerView) }
    }

    private fun showAddIntentionDialog() {
        val input = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 3
        }
        val layout = TextInputLayout(this).apply {
            setPadding(48, 24, 48, 0)
            addView(input)
            hint = "e.g., Fold the laundry"
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("New Intention")
            .setView(layout)
            .setPositiveButton("Add", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()

        val posBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        posBtn.isEnabled = false
        input.doAfterTextChanged { posBtn.isEnabled = !it.isNullOrBlank() }

        posBtn.setOnClickListener {
            val text = input.text?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                val newItem = IntentionItem(text = text)
                intentionAdapter.insertAt(0, newItem)
                binding.intentionRecyclerView.scrollToPosition(0)
                saveList()
                dialog.dismiss()
            }
        }
        input.requestFocus()
    }

    private fun completeIntention(item: IntentionItem) {
        val pos = intentionAdapter.items.indexOf(item)
        if (pos == -1) return
        val removed = intentionAdapter.removeAt(pos) ?: return
        saveList()

        Snackbar.make(binding.root, "Intention completed ✓", Snackbar.LENGTH_LONG)
            .setAction("Undo") {
                intentionAdapter.insertAt(pos, removed)
                saveList()
            }.show()
    }

    private fun confirmDelete(item: IntentionItem) {
        val pos = intentionAdapter.items.indexOf(item)
        if (pos == -1) return
        val removed = intentionAdapter.removeAt(pos) ?: return
        saveList()

        Snackbar.make(binding.root, "Intention deleted", Snackbar.LENGTH_LONG)
            .setAction("Undo") {
                intentionAdapter.insertAt(pos, removed)
                saveList()
            }.show()
    }

    private fun saveList() {
        lifecycleScope.launch { configRepository.updateIntentionList(intentionAdapter.items.toList()) }
    }

    // ─── Adapter ──────────────────────────────────────────────────────────────

    inner class IntentionAdapter(
        val onCompleteClick: (IntentionItem) -> Unit,
        val onDeleteClick: (IntentionItem) -> Unit,
        val onDragStart: (RecyclerView.ViewHolder) -> Unit
    ) : RecyclerView.Adapter<IntentionAdapter.IntentionViewHolder>() {

        val items = mutableListOf<IntentionItem>()

        fun moveItem(from: Int, to: Int) {
            if (from < 0 || to < 0 || from >= items.size || to >= items.size) return
            Collections.swap(items, from, to); notifyItemMoved(from, to)
        }
        fun removeAt(pos: Int): IntentionItem? {
            if (pos < 0 || pos >= items.size) return null
            val removed = items.removeAt(pos); notifyItemRemoved(pos); return removed
        }
        fun insertAt(pos: Int, item: IntentionItem) {
            val p = pos.coerceIn(0, items.size); items.add(p, item); notifyItemInserted(p)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            IntentionViewHolder(ItemIntentionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: IntentionViewHolder, position: Int) = holder.bind(items[position])
        override fun getItemCount() = items.size

        inner class IntentionViewHolder(private val b: ItemIntentionBinding) : RecyclerView.ViewHolder(b.root) {
            @Suppress("ClickableViewAccessibility")
            fun bind(intention: IntentionItem) {
                b.intentionNameText.text = intention.text
                b.btnCompleteIntention.setOnClickListener { onCompleteClick(intention) }
                b.btnDeleteIntention.setOnClickListener { onDeleteClick(intention) }
                b.imgDragHandle.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) onDragStart(this)
                    false
                }
            }
        }
    }
}