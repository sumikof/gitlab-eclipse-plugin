package com.gitlab.eclipse.utils

import org.eclipse.swt.widgets.Display
import org.eclipse.ui.PlatformUI

val currentDisplay: Display
  get() = Display.getCurrent() ?: PlatformUI.getWorkbench().display
