package com.gitlab.eclipse.utils

import com.gitlab.eclipse.mergerequests.GitOperationGuard
import com.gitlab.eclipse.preferences.PreferenceInitializer
import com.gitlab.eclipse.views.sidebar.SidebarViewState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import org.eclipse.core.runtime.preferences.InstanceScope
import org.eclipse.ui.preferences.ScopedPreferenceStore
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.osgi.framework.BundleContext

@Suppress("InjectDispatcher")
fun workspaceModule(bundleContext: BundleContext) = module {
  single<ScopedPreferenceStore> {
    ScopedPreferenceStore(
      InstanceScope.INSTANCE,
      bundleContext.bundle.bundleId.toString()
    ).also { store -> PreferenceInitializer(store).initializeDefaultPreferences() }
  }

  single<CoroutineScope> { CoroutineScope(Dispatchers.IO) }

  // Serializes GitLabLanguageServerConfigurationService's outbound didChangeConfiguration
  // notifications; see the "CALL time" comment on that class for why order matters.
  single<Mutex>(named("languageServerOutbound")) { Mutex() }

  single { PlatformUtils() }
  single { CodeFormatter(get()) }

  // Shared between GitLabSidebarView and the sidebar mode-toggle handlers: both must
  // observe/mutate the SAME view state, so it lives here as a singleton.
  single { SidebarViewState() }

  // Shared between MrBranchCheckoutService (checkout) and BranchPushService (push): the guard
  // only serializes git operations per repository if BOTH services see the SAME instance.
  single { GitOperationGuard() }
}
