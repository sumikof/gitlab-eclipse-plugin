package com.gitlab.eclipse.utils

import com.gitlab.eclipse.BuildConfig
import org.eclipse.ui.AbstractSourceProvider

class BuildFlagSourceProvider : AbstractSourceProvider() {
  companion object {
    private const val CODE_SUGGESTIONS_ENABLED_KEY = "code_suggestions_enabled"
  }

  override fun dispose() = Unit

  override fun getCurrentState() = mapOf(
    CODE_SUGGESTIONS_ENABLED_KEY to BuildConfig.CODE_SUGGESTIONS_ENABLED
  )

  override fun getProvidedSourceNames() = arrayOf(CODE_SUGGESTIONS_ENABLED_KEY)
}
