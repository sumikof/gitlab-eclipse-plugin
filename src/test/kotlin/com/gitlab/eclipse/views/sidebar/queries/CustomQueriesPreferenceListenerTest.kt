package com.gitlab.eclipse.views.sidebar.queries

import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.eclipse.jface.preference.IPreferenceStore
import org.eclipse.jface.util.IPropertyChangeListener
import org.eclipse.jface.util.PropertyChangeEvent
import java.util.concurrent.atomic.AtomicInteger

private class StoreHarness {
  val store = mockk<IPreferenceStore>()
  val added = slot<IPropertyChangeListener>()
  val calls = AtomicInteger()

  @Volatile var callbackThread: Thread? = null
  val listener = CustomQueriesPreferenceListener(store) {
    callbackThread = Thread.currentThread()
    calls.incrementAndGet()
  }

  init {
    every { store.addPropertyChangeListener(capture(added)) } just runs
    every { store.removePropertyChangeListener(any()) } just runs
  }

  fun fire(property: String) = added.captured.propertyChange(PropertyChangeEvent(store, property, "old", "new"))
}

class CustomQueriesPreferenceListenerTest : StringSpec({
  "forwards a change of the custom queries key" {
    val h = StoreHarness()
    h.listener.register()
    h.fire(PreferenceConstants.SIDEBAR_CUSTOM_QUERIES)
    h.calls.get() shouldBe 1
  }

  "ignores events for other keys" {
    val h = StoreHarness()
    h.listener.register()
    h.fire("gitlab.some.other.key")
    h.calls.get() shouldBe 0
  }

  "register twice adds exactly one listener" {
    val h = StoreHarness()
    h.listener.register()
    h.listener.register()
    verify(exactly = 1) { h.store.addPropertyChangeListener(any()) }
  }

  "dispose removes the same instance that was added, once" {
    val h = StoreHarness()
    h.listener.register()
    h.listener.dispose()
    h.listener.dispose()
    verify(exactly = 1) { h.store.removePropertyChangeListener(h.added.captured) }
  }

  "dispose before register removes nothing" {
    val h = StoreHarness()
    h.listener.dispose()
    verify(exactly = 0) { h.store.removePropertyChangeListener(any()) }
  }

  "register after dispose adds nothing" {
    val h = StoreHarness()
    h.listener.dispose()
    h.listener.register()
    verify(exactly = 0) { h.store.addPropertyChangeListener(any()) }
  }

  "an event delivered on a background thread still calls back synchronously on that thread" {
    val h = StoreHarness()
    h.listener.register()
    val worker = Thread { h.fire(PreferenceConstants.SIDEBAR_CUSTOM_QUERIES) }
    worker.start()
    worker.join()
    h.callbackThread shouldBe worker
    h.calls.get() shouldBe 1
  }
})
