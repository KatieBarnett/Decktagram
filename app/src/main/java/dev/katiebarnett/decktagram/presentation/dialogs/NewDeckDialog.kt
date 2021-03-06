package dev.katiebarnett.decktagram.presentation.dialogs

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.katiebarnett.decktagram.R
import dev.katiebarnett.decktagram.databinding.NewDeckDialogBinding

class NewDeckDialog : DialogFragment() {
    
    companion object {
        const val TAG = "NewDeckDialog"
    }
    
    internal var listener: DialogListener? = null
    
    private lateinit var binding: NewDeckDialogBinding

    interface DialogListener {
        fun onDialogPositiveClick(deckName: String)
    }
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {
            val builder = MaterialAlertDialogBuilder(it)
            binding = NewDeckDialogBinding.inflate(LayoutInflater.from(context))
            builder.setView(binding.root)
                // Add action buttons
                .setPositiveButton(R.string.game_dialog_button_save) { dialog, id ->
                    listener?.onDialogPositiveClick(binding.nameField.editText?.text.toString().trim())
                }
                .setNegativeButton(R.string.game_dialog_button_cancel) { dialog, id ->
                    dialog.cancel()
                }
            builder.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }
    
    fun setListener(listener: DialogListener) {
        this.listener = listener
    }
}