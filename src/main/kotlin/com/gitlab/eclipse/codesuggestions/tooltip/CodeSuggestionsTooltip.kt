package com.gitlab.eclipse.codesuggestions.tooltip

import com.gitlab.eclipse.BuildConfig
import com.gitlab.eclipse.codesuggestions.CodeSuggestionsSession
import com.gitlab.eclipse.utils.logger
import org.eclipse.swt.SWT
import org.eclipse.swt.custom.StyledText
import org.eclipse.swt.events.MouseEvent
import org.eclipse.swt.events.MouseTrackAdapter
import org.eclipse.swt.layout.GridData
import org.eclipse.swt.layout.GridLayout
import org.eclipse.swt.widgets.Label
import org.eclipse.swt.widgets.Listener
import org.eclipse.swt.widgets.Shell

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
  private var isVisible = false

  private val mouseTrackListener = object : MouseTrackAdapter() {
    override fun mouseHover(e: MouseEvent) {
      try {
        if (isVisible) return

        if (session.isCodeSuggestionDisplayed() && isMouseOverCodeSuggestion(e.y)) {
          show()
        }
      } catch (e: Exception) {
        logger.error("Error in mouse hover handler.", e)
      }
    }
  }

  private val globalClickListener = Listener { event ->
    if (tipShell?.isDisposed == false && tipShell?.visible == true) {
      val control = event.widget

      if (control !is Shell || control != tipShell) {
        val clickPosition = textWidget.display.getCursorLocation()
        val tipBounds = tipShell?.bounds

        if (tipBounds == null || !tipBounds.contains(clickPosition) || !isClickInsideIDE(control)) {
          hide()
        }
      }
    }
  }

  private val focusListener = Listener { if (isVisible) hide() }

  init {
    textWidget.addMouseTrackListener(mouseTrackListener)
    textWidget.display.addFilter(SWT.MouseDown, globalClickListener)
    textWidget.addListener(SWT.FocusOut, focusListener)
    textWidget.shell.addListener(SWT.Deactivate, focusListener)
  }

  fun hide() {
    if (isVisible) {
      tipShell?.visible = false
      isVisible = false
    }
  }

  fun dispose() {
    try {
      textWidget.removeMouseTrackListener(mouseTrackListener)
      textWidget.display.removeFilter(SWT.MouseDown, globalClickListener)
      textWidget.removeListener(SWT.FocusOut, focusListener)
      textWidget.shell.removeListener(SWT.Deactivate, focusListener)
    } catch (e: Exception) {
      logger.error("Error disposing code suggestions tooltip.", e)
    } finally {
      tipShell?.dispose()
      tipShell = null
    }
  }

  private fun show() {
    try {
      if (!isEnabled) return

      if (tipShell == null || tipShell?.isDisposed == true) {
        val display = textWidget.display
        val tipShell = Shell(textWidget.shell, SWT.ON_TOP or SWT.TOOL).also { this.tipShell = it }

        val gridLayout = GridLayout().apply {
          numColumns = 3
          marginWidth = 5
          marginHeight = 2
        }
        tipShell.layout = gridLayout
        tipShell.background = display.getSystemColor(SWT.COLOR_INFO_BACKGROUND)

        Label(tipShell, SWT.NONE).apply {
          text = "Cycle suggestions"
          foreground = textWidget.display.getSystemColor(SWT.COLOR_INFO_FOREGROUND)
          background = textWidget.display.getSystemColor(SWT.COLOR_INFO_BACKGROUND)
          layoutData = GridData(GridData.HORIZONTAL_ALIGN_CENTER)
        }

        tipShell.pack()
      }

      val shell = checkNotNull(tipShell)

      val displayBounds = shell.display.getBounds()
      val shellBounds = shell.bounds

      val suggestionOffset = session.getCodeSuggestionPosition()?.offset ?: return

      val locationAtOffset = textWidget.getLocationAtOffset(suggestionOffset)
      val suggestionPoint = textWidget.toDisplay(locationAtOffset)

      var x = suggestionPoint.x
      var y = suggestionPoint.y - shellBounds.height - 5

      x = x.coerceIn(0, displayBounds.width - shellBounds.width)
      y = y.coerceIn(0, displayBounds.height - shellBounds.height)

      shell.setLocation(x, y)

      tipShell?.visible = true
      isVisible = true
    } catch (e: Exception) {
      logger.error("Error showing tooltip.", e)
    }
  }

  private fun isClickInsideIDE(widget: Any?): Boolean {
    return widget is org.eclipse.swt.widgets.Widget
  }

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
