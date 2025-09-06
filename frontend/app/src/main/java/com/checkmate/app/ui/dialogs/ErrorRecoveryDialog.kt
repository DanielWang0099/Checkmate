package com.checkmate.app.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.checkmate.app.utils.ErrorRecoveryManager
import kotlinx.coroutines.launch

/**
 * Simple error dialog with recovery actions and retry mechanisms
 */
class ErrorRecoveryDialog : DialogFragment() {
    
    private lateinit var errorRecoveryManager: ErrorRecoveryManager
    private lateinit var enhancedError: ErrorRecoveryManager.EnhancedError
    private var onRetryCallback: (() -> Unit)? = null
    private var onDismissCallback: (() -> Unit)? = null
    
    companion object {
        fun newInstance(
            error: ErrorRecoveryManager.EnhancedError,
            onRetry: (() -> Unit)? = null,
            onDismiss: (() -> Unit)? = null
        ): ErrorRecoveryDialog {
            val dialog = ErrorRecoveryDialog()
            dialog.enhancedError = error
            dialog.onRetryCallback = onRetry
            dialog.onDismissCallback = onDismiss
            return dialog
        }
    }
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        errorRecoveryManager = ErrorRecoveryManager(requireContext())
        
        return AlertDialog.Builder(requireContext())
            .setTitle(when (enhancedError.severity) {
                ErrorRecoveryManager.ErrorSeverity.LOW -> "Minor Issue"
                ErrorRecoveryManager.ErrorSeverity.MEDIUM -> "Connection Problem"
                ErrorRecoveryManager.ErrorSeverity.HIGH -> "Service Error"
                ErrorRecoveryManager.ErrorSeverity.CRITICAL -> "Critical Error"
            })
            .setMessage(enhancedError.userMessage)
            .setPositiveButton("Retry") { _: DialogInterface, _: Int ->
                if (enhancedError.canRetry) {
                    handleRetry()
                }
            }
            .setNegativeButton("Dismiss") { _: DialogInterface, _: Int ->
                handleDismiss()
            }
            .setNeutralButton("More Options") { _: DialogInterface, _: Int ->
                showRecoveryActions()
            }
            .setCancelable(true)
            .create()
    }
    
    private fun handleRetry() {
        lifecycleScope.launch {
            try {
                onRetryCallback?.invoke()
                Toast.makeText(requireContext(), "Operation succeeded!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Retry failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun showRecoveryActions() {
        val actions = enhancedError.recoveryActions.filter { !it.autoExecutable }
        if (actions.isNotEmpty()) {
            val actionNames = actions.map { it.description }.toTypedArray()
            
            AlertDialog.Builder(requireContext())
                .setTitle("Recovery Actions")
                .setItems(actionNames) { _: DialogInterface, which: Int ->
                    val selectedAction = actions[which]
                    executeRecoveryAction(selectedAction)
                }
                .show()
        }
    }
    
    private fun executeRecoveryAction(action: ErrorRecoveryManager.RecoveryAction) {
        lifecycleScope.launch {
            try {
                val success = errorRecoveryManager.executeRecoveryAction(action, enhancedError.context)
                val message = if (success) {
                    "✓ ${action.description} completed"
                } else {
                    "✗ ${action.description} failed"
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Action failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun handleDismiss() {
        onDismissCallback?.invoke()
        dismiss()
    }
}
