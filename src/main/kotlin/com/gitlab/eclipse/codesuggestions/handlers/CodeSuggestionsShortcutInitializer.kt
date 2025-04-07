package com.gitlab.eclipse.codesuggestions.handlers

import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.Command
import org.eclipse.core.commands.ParameterizedCommand
import org.eclipse.jface.bindings.Binding
import org.eclipse.jface.bindings.keys.KeyBinding
import org.eclipse.jface.bindings.keys.KeySequence
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.commands.ICommandService
import org.eclipse.ui.keys.IBindingService

class CodeSuggestionsShortcutInitializer {
  private val logger by lazy { logger<CodeSuggestionsShortcutInitializer>() }

  init {
    register()
  }

  private fun register() {
    val bindingService = PlatformUI.getWorkbench().getAdapter<IBindingService?>(IBindingService::class.java)
      ?: return logger.warn("Binding service is not available. Partially accepting code suggestions is disabled.")

    val commandService = PlatformUI.getWorkbench().getAdapter<ICommandService?>(ICommandService::class.java)
      ?: return logger.warn("Command service is not available. Partially accepting code suggestions is disabled.")

    val bindings = mutableListOf(*bindingService.bindings)

    bindings.add(
      createBinding(
        schemeId = bindingService.activeScheme.id,
        sequence = "M1+ARROW_RIGHT",
        command = commandService.getCommand("com.gitlab.eclipse.codesuggestions.acceptSuggestionLine")
      )
    )

    bindingService.savePreferences(bindingService.activeScheme, bindings.toTypedArray())
  }

  private fun createBinding(schemeId: String, sequence: String, command: Command): KeyBinding {
    return KeyBinding(
      KeySequence.getInstance(sequence),
      ParameterizedCommand(command, null),
      schemeId,
      "org.eclipse.ui.textEditorScope",
      null,
      null,
      null,
      Binding.USER
    )
  }
}
