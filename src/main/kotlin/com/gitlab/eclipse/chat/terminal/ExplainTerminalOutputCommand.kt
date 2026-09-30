package com.gitlab.eclipse.chat.terminal

import com.gitlab.eclipse.lsp.NewPromptRequest

/**
 * "Explain Terminal Output with Duo", free of SWT and the workbench so that it runs headless.
 *
 * Keeps no state between runs, and never changes the clipboard or the terminal's selection: on any
 * failure the user's text is still selected where it was, and running the command again retries.
 *
 * Nothing it logs or shows contains the selected text or its length — only the outcome and type
 * names.
 *
 * @param isAvailable the runtime gate; checked before the selection is even read.
 * @param send hands the prompt to the Duo Chat view (the same path as "Explain Code").
 */
class ExplainTerminalOutputCommand(
  private val isAvailable: () -> Boolean,
  private val readSelection: (Any?) -> TerminalSelection = TerminalSelectionReader::read,
  private val send: (NewPromptRequest) -> Unit,
  private val notify: (String) -> Unit,
  private val log: (String) -> Unit,
) {
  enum class Outcome { NOT_AVAILABLE, NO_SELECTION, UNREADABLE, SENT, SENT_TRUNCATED, SEND_FAILED }

  companion object {
    const val NOT_AVAILABLE_MESSAGE =
      "Explaining terminal output with GitLab Duo is not available. Check GitLab Duo Chat and its terminal context setting."
    const val NO_SELECTION_MESSAGE = "Select the terminal output to explain, then try again."
    const val UNREADABLE_MESSAGE = "Could not read the selected text from this view."
    const val TRUNCATED_MESSAGE = "The selected output was too long. Only its last part was sent to GitLab Duo Chat."
    const val SEND_FAILED_MESSAGE = "Could not send the terminal output to GitLab Duo Chat. Your selection is unchanged."
  }

  @Suppress("TooGenericExceptionCaught")
  fun run(menuSelection: Any?): Outcome {
    if (!isAvailable()) {
      notify(NOT_AVAILABLE_MESSAGE)
      return Outcome.NOT_AVAILABLE
    }

    val text = when (val selection = readSelection(menuSelection)) {
      is TerminalSelection.Text -> selection.value
      TerminalSelection.Empty -> {
        notify(NO_SELECTION_MESSAGE)
        return Outcome.NO_SELECTION
      }
      is TerminalSelection.Unavailable -> {
        log("Explain terminal output: cannot read the selection (${selection.reason})")
        notify(UNREADABLE_MESSAGE)
        return Outcome.UNREADABLE
      }
    }

    val built = TerminalOutputPrompt.build(text)
    try {
      send(built.request)
    } catch (e: Exception) {
      log("Explain terminal output: cannot send the prompt, type=${e.javaClass.name}")
      notify(SEND_FAILED_MESSAGE)
      return Outcome.SEND_FAILED
    }

    if (built.truncated) {
      notify(TRUNCATED_MESSAGE)
      return Outcome.SENT_TRUNCATED
    }
    return Outcome.SENT
  }
}
