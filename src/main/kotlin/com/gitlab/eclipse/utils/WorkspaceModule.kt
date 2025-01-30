package com.gitlab.eclipse.utils

import com.gitlab.eclipse.preferences.PreferenceInitializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.eclipse.core.runtime.preferences.InstanceScope
import org.eclipse.ui.preferences.ScopedPreferenceStore
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

  single { TextEditorProvider() }
}
