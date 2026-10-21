package com.gitlab.eclipse.codesuggestions.status

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.lifecycle.LanguageServerLifecycleState
import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.eclipse.e4.core.services.events.IEventBroker
import org.eclipse.e4.ui.css.swt.theme.IThemeEngine
import org.eclipse.jface.preference.IPreferenceStore
import org.eclipse.jface.util.IPropertyChangeListener
import org.eclipse.jface.util.PropertyChangeEvent
import org.eclipse.ui.IEditorReference
import org.eclipse.ui.IPartListener2
import org.eclipse.ui.IPartService
import org.eclipse.ui.ISourceProvider
import org.eclipse.ui.ISourceProviderListener
import org.eclipse.ui.IViewReference
import org.eclipse.ui.IWindowListener
import org.eclipse.ui.IWorkbench
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.themes.IThemeManager
import org.koin.core.error.NoDefinitionFoundException
import org.osgi.service.event.Event
import org.osgi.service.event.EventHandler

private const val SHOW_STATUS = "gitlab-eclipse-plugin.commands.showPluginStatus"
private const val CS_STATUS = "gitlab-eclipse-plugin.commands.codeSuggestionsStatus"

/** Headless: the UI hop, the guards and the workbench are seams; no SWT Display is touched. */
class DuoStatusRefresherTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  class Fixture {
    var closed = false
    var workbenchAvailable = true
    val queued = mutableListOf<Runnable>()
    var dispatchFailure: Exception? = null
    val refreshed = mutableListOf<String>()
    var refreshFailure: (String) -> Exception? = { null }

    val refresher = DuoStatusRefresher(
      isStoreClosed = { closed },
      isWorkbenchAvailable = { workbenchAvailable },
      uiDispatch = { runnable ->
        dispatchFailure?.let { throw it }
        queued += runnable
      },
      refreshElements = { id ->
        refreshFailure(id)?.let { throw it }
        refreshed += id
      },
    )

    fun drain() {
      while (queued.isNotEmpty()) queued.removeAt(0).run()
    }
  }

  describe("request") {
    it("coalesces any number of requests into one dispatch until it runs") {
      val f = Fixture()
      repeat(5) { f.refresher.request() }

      f.queued shouldHaveSize 1
      f.refreshed.shouldBeEmpty()

      f.drain()
      f.refreshed shouldContainExactly listOf(SHOW_STATUS, CS_STATUS)

      f.refresher.request()
      f.queued shouldHaveSize 1
    }

    it("refreshes both the trim item and the menu label command ids") {
      DuoStatusRefresher.COMMAND_IDS shouldContainExactly listOf(SHOW_STATUS, CS_STATUS)
    }

    it("a request made while the refresh runs schedules one more refresh") {
      val f = Fixture()
      var nested = true
      f.refreshFailure = { id ->
        if (id == CS_STATUS && nested) {
          nested = false
          f.refresher.request()
        }
        null
      }
      f.refresher.request()
      f.queued.removeAt(0).run()

      f.queued shouldHaveSize 1
    }

    it("does not dispatch when the lifecycle store is closed") {
      val f = Fixture()
      f.closed = true
      f.refresher.request()
      f.queued.shouldBeEmpty()
    }

    it("does not dispatch when the workbench is not running or closing") {
      val f = Fixture()
      f.workbenchAvailable = false
      f.refresher.request()
      f.queued.shouldBeEmpty()
    }

    it("does not refresh when the workbench starts closing before the dispatched runnable runs") {
      val f = Fixture()
      f.refresher.request()
      f.workbenchAvailable = false
      f.drain()
      f.refreshed.shouldBeEmpty()
    }

    it("does not refresh when the store closes before the dispatched runnable runs") {
      val f = Fixture()
      f.refresher.request()
      f.closed = true
      f.drain()
      f.refreshed.shouldBeEmpty()
    }

    it("contains a failing dispatch and accepts the next request") {
      val f = Fixture()
      f.dispatchFailure = IllegalStateException()
      shouldNotThrowAny { f.refresher.request() }

      f.dispatchFailure = null
      f.refresher.request()
      f.queued shouldHaveSize 1
    }

    it("contains a failing guard") {
      val refresher = DuoStatusRefresher(
        isStoreClosed = { false },
        isWorkbenchAvailable = { throw IllegalStateException() },
        uiDispatch = { error("must not dispatch") },
        refreshElements = { error("must not refresh") },
      )
      shouldNotThrowAny { refresher.request() }
    }

    it("contains a failing refresh of one id, still refreshes the other, and accepts the next request") {
      val f = Fixture()
      f.refreshFailure = { id -> if (id == SHOW_STATUS) IllegalArgumentException() else null }
      f.refresher.request()
      shouldNotThrowAny { f.drain() }

      f.refreshed shouldContainExactly listOf(CS_STATUS)
      f.refresher.request()
      f.queued shouldHaveSize 1
    }

    it("contains a checked exception (a Koin failure) from the dispatch, the guard and a refresh") {
      val f = Fixture()
      f.dispatchFailure = NoDefinitionFoundException("koin")
      shouldNotThrowAny { f.refresher.request() }
      f.dispatchFailure = null
      f.refreshFailure = { id -> if (id == SHOW_STATUS) NoDefinitionFoundException("koin") else null }
      f.refresher.request()
      shouldNotThrowAny { f.drain() }
      f.refreshed shouldContainExactly listOf(CS_STATUS)

      val guarded = DuoStatusRefresher(
        isStoreClosed = { throw NoDefinitionFoundException("koin") },
        uiDispatch = { error("must not dispatch") },
        refreshElements = { error("must not refresh") },
      )
      shouldNotThrowAny { guarded.request() }
    }

    it("is a lifecycle listener that requests a refresh on every transition") {
      val f = Fixture()
      f.refresher.onTransition(LanguageServerLifecycleState.INITIAL, LanguageServerLifecycleState.INITIAL)
      f.queued shouldHaveSize 1
    }

    it("never dispatches after dispose, and a pending runnable no longer refreshes") {
      val f = Fixture()
      f.refresher.request()
      f.refresher.dispose()
      f.drain()
      f.refreshed.shouldBeEmpty()

      f.refresher.request()
      f.queued.shouldBeEmpty()
    }
  }

  describe("installWindowListeners / dispose") {
    class Ui {
      val windowListener = slot<IWindowListener>()
      val themeListener = slot<IPropertyChangeListener>()
      val prefListener = slot<IPropertyChangeListener>()
      val sourceListener = slot<ISourceProviderListener>()
      val themeTopic = slot<String>()
      val themeHandler = slot<EventHandler>()
      val partListeners = mutableMapOf<IWorkbenchWindow, IPartListener2>()
      val removedPartListeners = mutableListOf<IWorkbenchWindow>()

      fun window(): IWorkbenchWindow {
        val window = mockk<IWorkbenchWindow>()
        val partService = mockk<IPartService>()
        every { window.partService } returns partService
        every { partService.addPartListener(any<IPartListener2>()) } answers {
          partListeners[window] = firstArg()
        }
        every { partService.removePartListener(any<IPartListener2>()) } answers { removedPartListeners += window }
        return window
      }

      val first = window()
      val themeManager = mockk<IThemeManager> {
        every { addPropertyChangeListener(capture(themeListener)) } just runs
        every { removePropertyChangeListener(any()) } just runs
      }
      val broker = mockk<IEventBroker> {
        every { subscribe(capture(themeTopic), capture(themeHandler)) } returns true
        every { unsubscribe(any()) } returns true
      }
      val workbench = mockk<IWorkbench> {
        every { addWindowListener(capture(windowListener)) } just runs
        every { removeWindowListener(any()) } just runs
        every { workbenchWindows } returns arrayOf(first)
        every { themeManager } returns this@Ui.themeManager
        every { getService(IEventBroker::class.java) } returns broker
      }
      val authSource = mockk<ISourceProvider> {
        every { addSourceProviderListener(capture(sourceListener)) } just runs
        every { removeSourceProviderListener(any()) } just runs
      }
      val prefs = mockk<IPreferenceStore> {
        every { addPropertyChangeListener(capture(prefListener)) } just runs
        every { removePropertyChangeListener(any()) } just runs
      }
      val queued = mutableListOf<Runnable>()
      val refresher = DuoStatusRefresher(
        isStoreClosed = { false },
        isWorkbenchAvailable = { true },
        uiDispatch = { queued += it },
        refreshElements = {},
        workbench = { workbench },
        authSource = { authSource },
        preferenceStore = { prefs },
      )

      fun takeRequests(): Int {
        val n = queued.size
        queued.forEach { it.run() }
        queued.clear()
        return n
      }
    }

    it("listens to every existing window's parts and requests a refresh on editor activation") {
      val ui = Ui()
      ui.refresher.installWindowListeners()

      val listener = ui.partListeners.getValue(ui.first)
      listener.partActivated(mockk<IEditorReference>())
      ui.takeRequests() shouldBe 1
      listener.partClosed(mockk<IEditorReference>())
      ui.takeRequests() shouldBe 1
      listener.partInputChanged(mockk<IEditorReference>())
      ui.takeRequests() shouldBe 1
      listener.partActivated(mockk<IViewReference>())
      ui.takeRequests() shouldBe 0
    }

    it("tracks a newly opened window and requests a refresh for its trim (F7)") {
      val ui = Ui()
      ui.refresher.installWindowListeners()
      val second = ui.window()

      ui.windowListener.captured.windowOpened(second)

      ui.partListeners.keys shouldBe setOf(ui.first, second)
      ui.takeRequests() shouldBe 1
    }

    it("stops listening to a closed window") {
      val ui = Ui()
      ui.refresher.installWindowListeners()
      ui.windowListener.captured.windowClosed(ui.first)
      ui.removedPartListeners shouldContainExactly listOf(ui.first)
    }

    it("requests a refresh on a theme change, an auth source change and a Code Suggestions preference change") {
      val ui = Ui()
      ui.refresher.installWindowListeners()

      ui.themeListener.captured.propertyChange(
        PropertyChangeEvent(this, IThemeManager.CHANGE_CURRENT_THEME, null, null),
      )
      ui.takeRequests() shouldBe 1

      ui.sourceListener.captured.sourceChanged(0, "gitlab_sign_in_required", true)
      ui.takeRequests() shouldBe 1
      ui.sourceListener.captured.sourceChanged(0, emptyMap<Any, Any>())
      ui.takeRequests() shouldBe 1

      for (key in listOf(
        PreferenceConstants.CODE_SUGGESTIONS_ENABLED,
        PreferenceConstants.CODE_SUGGESTIONS_DISABLED_SUPPORTED_LANGUAGES,
        PreferenceConstants.CODE_SUGGESTIONS_ADDITIONAL_LANGUAGES,
      )) {
        ui.prefListener.captured.propertyChange(PropertyChangeEvent(this, key, null, null))
        ui.takeRequests() shouldBe 1
      }
      ui.prefListener.captured.propertyChange(PropertyChangeEvent(this, "gitlab.url", null, null))
      ui.takeRequests() shouldBe 0
    }

    it("requests a refresh when Appearance > Theme switches the CSS theme (IThemeEngine THEME_CHANGED)") {
      val ui = Ui()
      ui.refresher.installWindowListeners()

      ui.themeTopic.captured shouldBe IThemeEngine.Events.THEME_CHANGED
      ui.themeHandler.captured.handleEvent(Event(IThemeEngine.Events.THEME_CHANGED, emptyMap<String, Any>()))
      ui.takeRequests() shouldBe 1
    }

    it("a checked exception (a Koin failure) in the auth source step does not stop the others (F7 still refreshes)") {
      val ui = Ui()
      val refresher = DuoStatusRefresher(
        isStoreClosed = { false },
        isWorkbenchAvailable = { true },
        uiDispatch = { ui.queued += it },
        refreshElements = {},
        workbench = { ui.workbench },
        authSource = { throw NoDefinitionFoundException("koin") },
        preferenceStore = { ui.prefs },
      )

      shouldNotThrowAny { refresher.installWindowListeners() }

      verify { ui.prefs.addPropertyChangeListener(any()) }
      ui.windowListener.captured.windowOpened(ui.window())
      ui.takeRequests() shouldBe 1
    }

    it("installs only once") {
      val ui = Ui()
      ui.refresher.installWindowListeners()
      ui.refresher.installWindowListeners()
      verify(exactly = 1) { ui.workbench.addWindowListener(any()) }
    }

    it("dispose removes every listener it installed (I6)") {
      val ui = Ui()
      ui.refresher.installWindowListeners()
      val second = ui.window()
      ui.windowListener.captured.windowOpened(second)

      ui.refresher.dispose()

      verify { ui.workbench.removeWindowListener(ui.windowListener.captured) }
      verify { ui.themeManager.removePropertyChangeListener(ui.themeListener.captured) }
      verify { ui.authSource.removeSourceProviderListener(ui.sourceListener.captured) }
      verify { ui.prefs.removePropertyChangeListener(ui.prefListener.captured) }
      verify { ui.broker.unsubscribe(ui.themeHandler.captured) }
      ui.removedPartListeners.toSet() shouldBe setOf(ui.first, second)
    }

    it("dispose continues past a failing removal") {
      val ui = Ui()
      every { ui.workbench.removeWindowListener(any()) } throws IllegalStateException()
      ui.refresher.installWindowListeners()

      shouldNotThrowAny { ui.refresher.dispose() }
      verify { ui.prefs.removePropertyChangeListener(ui.prefListener.captured) }
      ui.removedPartListeners shouldContainExactly listOf(ui.first)
    }

    it("installing after dispose does nothing") {
      val ui = Ui()
      ui.refresher.dispose()
      ui.refresher.installWindowListeners()
      verify(exactly = 0) { ui.workbench.addWindowListener(any()) }
    }

    it("a failing install step does not prevent the others") {
      val ui = Ui()
      every { ui.workbench.themeManager } throws IllegalStateException()
      shouldNotThrowAny { ui.refresher.installWindowListeners() }
      ui.partListeners.keys shouldBe setOf(ui.first)
      verify { ui.prefs.addPropertyChangeListener(any()) }
    }
  }
})
