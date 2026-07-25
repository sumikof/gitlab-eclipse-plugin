package com.gitlab.eclipse.navigation

import com.gitlab.eclipse.utils.currentDisplay
import org.eclipse.swt.dnd.Clipboard
import org.eclipse.swt.dnd.TextTransfer

/** Writes text to the SWT clipboard. Marshals to the UI thread. */
class ClipboardWriter {
  fun write(text: String) {
    currentDisplay.asyncExec {
      val clipboard = Clipboard(currentDisplay)
      try {
        clipboard.setContents(arrayOf(text), arrayOf(TextTransfer.getInstance()))
      } finally {
        clipboard.dispose()
      }
    }
  }
}
