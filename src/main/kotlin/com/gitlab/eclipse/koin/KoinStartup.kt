package com.gitlab.eclipse.koin

import com.gitlab.eclipse.EagerService
import com.gitlab.eclipse.lsp.GitLabLanguageServerProcessProvider
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.preferences.PreferenceInitializer
import org.eclipse.core.runtime.preferences.InstanceScope
import org.eclipse.ui.IStartup
import org.eclipse.ui.preferences.ScopedPreferenceStore
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.osgi.framework.FrameworkUtil

class KoinStartup : IStartup {
  override fun earlyStartup() {
    startKoin {
      modules(
        module {
          single<GitLabLanguageServerWrapper> { GitLabLanguageServerWrapper() }
          single<ScopedPreferenceStore> {
            ScopedPreferenceStore(
              InstanceScope.INSTANCE,
              FrameworkUtil.getBundle(GitLabLanguageServerProcessProvider::class.java).bundleId.toString()
            ).also { PreferenceInitializer(it).initializeDefaultPreferences() }
          }
          single(createdAtStart = true) { EagerService() }
        }
      )
    }
  }
}
