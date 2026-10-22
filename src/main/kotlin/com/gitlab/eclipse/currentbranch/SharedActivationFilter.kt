package com.gitlab.eclipse.currentbranch

import com.gitlab.eclipse.views.sidebar.autorefresh.Registration

/**
 * The one display-wide `SWT.Activate` / `SWT.Deactivate` filter of all windows' activity sources (status-bar
 * design §4.2, R7): N windows install one filter, not N. [install] adds the filter with a fan-out callback
 * (production: [displayActivationFilter]); it runs lazily on the first [register] — the trim's `createControl`
 * may run before the plugin-start activation — and its registration is removed again when the last window
 * unregisters, or on [dispose].
 *
 * UI thread only. A failing install propagates to the caller (the refresher's `start()` rolls back and logs the
 * class); a failing subscriber is logged by class name and does not keep the others from being signalled.
 */
class SharedActivationFilter(
  private val install: (fanOut: () -> Unit) -> Registration,
  private val log: (String) -> Unit,
) {
  /** A subscription; compared by identity, so one window's signal registered twice stays two entries. */
  private class Subscriber(val signal: () -> Unit)

  private val subscribers = mutableListOf<Subscriber>()
  private var installed: Registration? = null
  private var disposed = false

  fun register(signal: () -> Unit): Registration {
    check(!disposed) { "The shared activation filter is disposed." }
    if (installed == null) installed = install(::fanOut)
    val subscriber = Subscriber(signal)
    subscribers += subscriber
    return Registration {
      if (subscribers.remove(subscriber) && subscribers.isEmpty()) uninstall()
    }
  }

  fun dispose() {
    disposed = true
    subscribers.clear()
    uninstall()
  }

  private fun fanOut() {
    subscribers.toList().forEach { subscriber ->
      try {
        subscriber.signal()
      } catch (e: Exception) {
        runCatching { log("Current-branch activation signal failed: ${e.javaClass.name}") }
      }
    }
  }

  private fun uninstall() {
    val registration = installed ?: return
    installed = null
    try {
      registration.unregister()
    } catch (e: Exception) {
      runCatching { log("Current-branch activation filter removal failed: ${e.javaClass.name}") }
    }
  }
}
