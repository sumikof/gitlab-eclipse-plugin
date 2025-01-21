package com.gitlab.eclipse.preferences

import org.eclipse.core.runtime.preferences.InstanceScope
import org.eclipse.ui.preferences.ScopedPreferenceStore
import org.osgi.framework.FrameworkUtil

class PreferenceStoreProvider {
  companion object {
    private val instance by lazy {
      ScopedPreferenceStore(
        InstanceScope.INSTANCE,
        FrameworkUtil.getBundle(PreferenceStoreProvider::class.java).bundleId.toString()
      ).also { store -> PreferenceInitializer(store).initializeDefaultPreferences() }
    }
  }

  fun get() = instance
}
