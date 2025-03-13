package com.gitlab.eclipse.utils.system

import org.eclipse.ui.AbstractSourceProvider

class SystemSourceProvider : AbstractSourceProvider() {
  companion object {
    private const val OS_KEY = "os"
  }

  override fun dispose() = Unit
  override fun getCurrentState() = mapOf(OS_KEY to SystemUtils.os.name.lowercase())
  override fun getProvidedSourceNames() = arrayOf(OS_KEY)
}
