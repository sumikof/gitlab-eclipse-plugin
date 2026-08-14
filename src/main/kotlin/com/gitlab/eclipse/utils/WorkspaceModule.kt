package com.gitlab.eclipse.utils

import com.gitlab.eclipse.mergerequests.GitOperationGuard
import com.gitlab.eclipse.preferences.PreferenceInitializer
import com.gitlab.eclipse.snippets.SnippetPatchApplyService
import com.gitlab.eclipse.views.sidebar.SidebarViewState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import org.eclipse.core.runtime.preferences.InstanceScope
import org.eclipse.ui.preferences.ScopedPreferenceStore
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.osgi.framework.BundleContext

/**
 * Koin qualifier of the `Mutex` that serialises everything the plugin sends to the language server.
 *
 * A `const` rather than a literal at each site: the qualifier is only ever resolved when a
 * notification is actually being sent, so a typo would surface as a runtime failure on the first
 * send instead of at build time.
 */
const val LANGUAGE_SERVER_OUTBOUND = "languageServerOutbound"

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
  single<Mutex>(named(LANGUAGE_SERVER_OUTBOUND)) { Mutex() }

  single { PlatformUtils() }
  single { CodeFormatter(get()) }

  // Shared between GitLabSidebarView and the sidebar mode-toggle handlers: both must
  // observe/mutate the SAME view state, so it lives here as a singleton.
  single { SidebarViewState() }

  // Shared between MrBranchCheckoutService (checkout), BranchPushService (push) and
  // SnippetPatchApplyService (patch apply): the guard only serializes git operations per
  // repository if ALL of them see the SAME instance.
  single { GitOperationGuard() }

  // Takes the shared guard, and a quarantine rooted under the bundle state location.
  single { SnippetPatchApplyService(get()) }
}
