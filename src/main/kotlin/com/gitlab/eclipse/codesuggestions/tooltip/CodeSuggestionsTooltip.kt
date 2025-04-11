package com.gitlab.eclipse.codesuggestions.tooltip

import com.gitlab.eclipse.BuildConfig
import com.gitlab.eclipse.codesuggestions.CodeSuggestionsSession
import com.gitlab.eclipse.codesuggestions.CycleDirection
import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.utils.logger
import org.eclipse.swt.SWT
import org.eclipse.swt.custom.StyledText
import org.eclipse.swt.events.MouseAdapter
import org.eclipse.swt.events.MouseEvent
import org.eclipse.swt.events.MouseTrackAdapter
import org.eclipse.swt.layout.RowLayout
import org.eclipse.swt.widgets.Label
import org.eclipse.swt.widgets.Shell
import org.eclipse.ui.keys.IBindingService

/**
 * Tooltip for cycling through code suggestions.
 * Appears when hovering over active code suggestions.
 * Based on HoverHelp.java from eclipse.platform.swt.
 */
@SuppressWarnings("MagicNumber")
class CodeSuggestionsTooltip(
  private val textWidget: StyledText,
  private val session: CodeSuggestionsSession,
  private val isEnabled: Boolean = BuildConfig.CODE_SUGGESTIONS_TOOLTIP_ENABLED
) {
  private val logger by lazy { logger<CodeSuggestionsTooltip>() }

  private var tipShell: Shell? = null
  private var suggestionCountLabel: Label? = null

  private val display get() = textWidget.display

  private val mouseTrackListener = object : MouseTrackAdapter() {
    override fun mouseHover(e: MouseEvent) {
      try {
        display.syncExec {
          if (tipShell?.visible == true) return@syncExec
        }

        if (session.isCodeSuggestionDisplayed() && isMouseOverCodeSuggestion(e.y)) {
          show()
        }
      } catch (e: Exception) {
        logger.error("Error in mouse hover handler.", e)
      }
    }
  }

  init {
    textWidget.addMouseTrackListener(mouseTrackListener)
  }

  fun hide() {
    display.syncExec {
      tipShell?.visible = false
    }
  }

  fun dispose() {
    try {
      textWidget.removeMouseTrackListener(mouseTrackListener)
    } catch (e: Exception) {
      logger.error("Error disposing code suggestions tooltip.", e)
    } finally {
      tipShell?.dispose()
      tipShell = null
    }
  }

  /**
   * Updates the suggestion count display in the tooltip
   * Safely handles UI updates on the display thread
   *
   * @param position Current position in the suggestions list (1-based)
   * @param totalCount Total number of available suggestions
   * @param hasLoadedAll Whether all suggestions have been loaded
   */
  fun updateSuggestionDisplay(position: Int, totalCount: Int, hasLoadedAll: Boolean) {
    try {
      display.syncExec {
        if (tipShell?.visible != true) return@syncExec

        suggestionCountLabel?.text = updateSuggestionCount(position, totalCount, hasLoadedAll)
      }
    } catch (e: Exception) {
      logger.error("Error updating suggestion numbers.", e)
    }
  }

  /**
   * Formats the suggestion count text based on current state
   *
   * @param position Current position (1-based index)
   * @param totalCount Total number of suggestions
   * @param hasLoadedAll Whether all additional suggestions have been loaded
   * @return Formatted text for display in the format "position/totalCount" or "1/?"
   */
  private fun updateSuggestionCount(position: Int, totalCount: Int, hasLoadedAll: Boolean): String =
    if (totalCount <= 1 && !hasLoadedAll) {
      // Show "?" when we have one suggestion but haven't loaded all yet
      "1/?"
    } else {
      // At this point either we have multiple suggestions or we've loaded all
      // and confirmed there's only one
      "$position/$totalCount"
    }

  /**
   * Creates and displays the tooltip above the current code suggestion
   * Constructs the UI components, positions the tooltip, and makes it visible
   */
  private fun show() {
    try {
      if (!isEnabled) return

      tipShell?.dispose()
      tipShell = null
      suggestionCountLabel = null

      val newTipShell = Shell(textWidget.shell, SWT.TOOL)
      tipShell = newTipShell

      newTipShell.layout = RowLayout()

      createNavigationButton(CycleDirection.PREVIOUS, "<")

      suggestionCountLabel = Label(newTipShell, SWT.NONE).apply {
        val (position, totalCount, hasLoadedAdditionalSuggestions) = session.getSuggestionInfo()
        text = updateSuggestionCount(position, totalCount, hasLoadedAdditionalSuggestions)
      }

      createNavigationButton(CycleDirection.NEXT, ">")

      PlatformUtils()
        .getWorkbench()
        .getAdapter(IBindingService::class.java)
        ?.getBestActiveBindingFormattedFor("com.gitlab.eclipse.codesuggestions.acceptSuggestion")
        ?.let { "Accept ($it)" }
        ?.let { shortcut ->
          Label(newTipShell, SWT.NONE).apply {
            text = shortcut
            cursor = display.getSystemCursor(SWT.CURSOR_HAND)

            addMouseListener(object : MouseAdapter() {
              override fun mouseUp(e: MouseEvent?) {
                session.acceptCodeSuggestion()
                hide()
              }
            })
          }
        }

      newTipShell.pack()

      val displayBounds = newTipShell.display.getBounds()
      val shellBounds = newTipShell.bounds

      val suggestionOffset = session.getCodeSuggestionPosition()?.offset ?: return

      val locationAtOffset = textWidget.getLocationAtOffset(suggestionOffset)
      val suggestionPoint = textWidget.toDisplay(locationAtOffset)

      var x = suggestionPoint.x
      var y = suggestionPoint.y - shellBounds.height - 5

      x = x.coerceIn(0, displayBounds.width - shellBounds.width)
      y = y.coerceIn(0, displayBounds.height - shellBounds.height)

      newTipShell.setLocation(x, y)
      newTipShell.visible = true

      session.loadAdditionalSuggestions { session.updateTooltip() }
    } catch (e: Exception) {
      logger.error("Error showing tooltip.", e)
    }
  }

  /**
   * Creates a navigation button for cycling through code suggestions
   *
   * @param direction Direction to cycle (NEXT or PREVIOUS)
   * @param buttonText Text to display on the button (">" or "<")
   * @return The created Label with appropriate event handlers
   */
  private fun createNavigationButton(direction: CycleDirection, buttonText: String): Label {
    return Label(tipShell, SWT.NONE).apply {
      text = buttonText
      cursor = display.getSystemCursor(SWT.CURSOR_HAND)

      addMouseListener(object : MouseAdapter() {
        override fun mouseUp(e: MouseEvent) {
          session.cycleCodeSuggestion(direction)
        }
      })
    }
  }

  /**
   * Check if the mouse is over a code suggestion at the given Y coordinate
   *
   * @param mouseY The Y coordinate of the mouse pointer
   * @return True if the mouse is over a code suggestion, false otherwise
   */
  private fun isMouseOverCodeSuggestion(mouseY: Int): Boolean {
    try {
      val suggestionPosition = session.getCodeSuggestionPosition() ?: return false

      val mouseLineIndex = textWidget.getLineIndex(mouseY)
      if (mouseLineIndex < 0) return false

      val suggestionOffset = suggestionPosition.offset
      val suggestionLineIndex = textWidget.getLineAtOffset(suggestionOffset)

      return mouseLineIndex == suggestionLineIndex
    } catch (e: Exception) {
      logger.error("Error checking if mouse is over a code suggestion.", e)
      return false
    }
  }
}
