package com.gitlab.eclipse.chat.toolbar

import com.gitlab.eclipse.chat.utils.openDuoChatWindow
import com.gitlab.eclipse.utils.ThemeUtils
import org.eclipse.swt.SWT
import org.eclipse.swt.events.MouseAdapter
import org.eclipse.swt.events.MouseEvent
import org.eclipse.swt.layout.GridLayout
import org.eclipse.swt.widgets.Composite
import org.eclipse.swt.widgets.Control
import org.eclipse.swt.widgets.Label
import org.eclipse.ui.menus.WorkbenchWindowControlContribution

@Suppress("MagicNumber")
class DuoChatToolbarContribution : WorkbenchWindowControlContribution(
  "com.gitlab.eclipse.toolbar.duo-chat.contribution"
) {
  override fun createControl(parent: Composite): Control? {
    val borderComposite = Composite(parent, SWT.NONE).apply {
      layout = GridLayout().apply {
        marginHeight = 3
        marginRight = 1
      }
    }

    return Label(borderComposite, SWT.NONE).apply {
      image = ThemeUtils.getThemedIcon("chaton_obj")?.createImage()
      toolTipText = "Open Duo Chat"

      addMouseListener(
        object : MouseAdapter() {
          override fun mouseDown(e: MouseEvent) {
            openDuoChatWindow()
          }
        }
      )
    }
  }
}
