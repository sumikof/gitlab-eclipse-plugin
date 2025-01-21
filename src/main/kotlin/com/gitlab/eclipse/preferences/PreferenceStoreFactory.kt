package com.gitlab.eclipse.preferences

import com.gitlab.eclipse.di.annotations.Factory
import com.gitlab.eclipse.di.annotations.SingletonFactory
import org.eclipse.core.runtime.preferences.InstanceScope
import org.eclipse.ui.preferences.ScopedPreferenceStore
import org.osgi.framework.FrameworkUtil

@Factory
class PreferenceStoreFactory : SingletonFactory<ScopedPreferenceStore> {
  override fun create(): ScopedPreferenceStore {
    println("ScopedPreferenceStore created")

    return ScopedPreferenceStore(
      InstanceScope.INSTANCE,
      FrameworkUtil.getBundle(PreferenceStoreFactory::class.java).bundleId.toString()
    ).also { store -> PreferenceInitializer(store).initializeDefaultPreferences() }
  }
}
