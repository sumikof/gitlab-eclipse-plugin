package com.gitlab.eclipse.views.sidebar.queries

import com.gitlab.eclipse.preferences.PreferenceConstants
import org.eclipse.jface.preference.IPreferenceStore
import org.eclipse.jface.util.IPropertyChangeListener

/**
 * Forwards changes of the custom queries setting ([PreferenceConstants.SIDEBAR_CUSTOM_QUERIES]) in [store] to
 * [onChange] (design §8). The store may fire on any thread and [onChange] is called synchronously on that thread:
 * hopping to the UI thread is the caller's job. [register] and [dispose] are idempotent and called on the UI thread;
 * after [dispose], [register] does nothing, so a view instance owns at most one registration in its lifetime.
 */
class CustomQueriesPreferenceListener(private val store: IPreferenceStore, private val onChange: () -> Unit) {
  private val listener = IPropertyChangeListener { event ->
    if (event.property == PreferenceConstants.SIDEBAR_CUSTOM_QUERIES) onChange()
  }

  private var registered = false
  private var disposed = false

  fun register() {
    if (registered || disposed) return
    store.addPropertyChangeListener(listener)
    registered = true
  }

  fun dispose() {
    if (disposed) return
    disposed = true
    if (registered) store.removePropertyChangeListener(listener)
    registered = false
  }
}
