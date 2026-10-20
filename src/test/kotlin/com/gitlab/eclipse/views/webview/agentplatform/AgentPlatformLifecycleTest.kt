package com.gitlab.eclipse.views.webview.agentplatform

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.preferences.storage.CapturedLog
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

private class LifecycleMemoryAccess(var stored: String? = null) : LayoutPreferenceAccess {
  override fun read(): String? = stored
  override fun write(value: String) {
    stored = value
  }
}

/** The language server as the views see it: the current session, or `null` while stopped. */
private class LifecycleServer {
  var current: LanguageServerSession? = null

  /** When false, loads stay pending until [LifecycleHost.complete], like a metadata request in flight. */
  var completesAtOnce = true
}

/**
 * A stand-in for `WebviewBrowserHost` + its pipeline: latest-wins, a load on a stopped server
 * leaves a message page (no displayed session), and an applied url reports its session to
 * [onApplied] in the same turn, like the pipeline's Applied branch.
 */
private class LifecycleHost(private val server: LifecycleServer) {
  val loads = mutableListOf<String>()
  var displayedSession: LanguageServerSession? = null
  var displayedId: String? = null
  var onApplied: (LanguageServerSession) -> Unit = {}
  private var pending: String? = null

  fun load(id: String) {
    loads += id
    pending = id
    if (server.completesAtOnce) complete()
  }

  fun complete() {
    val id = pending ?: return
    pending = null
    val session = server.current ?: return
    displayedSession = session
    displayedId = id
    onApplied(session)
  }
}

/** One open view: the real surface over a fake host, wired the way `AgenticTabsView` wires it. */
private class LifecycleView(
  store: AgentPlatformLayoutStore,
  server: LifecycleServer,
  readiness: AgentPlatformReadiness,
) {
  val host = LifecycleHost(server)
  val surface = AgentPlatformSurface(store, host::load) { host.displayedSession }

  init {
    host.onApplied = { session -> surface.pageApplied(session, readiness::expect) }
    AgentPlatformViews.register(surface)
    surface.start()
  }

  fun close() {
    surface.dispose()
    AgentPlatformViews.unregister(surface)
  }
}

private class LifecycleFixture(initial: AgentPlatformLayout = AgentPlatformLayout.NEW) {
  val store = AgentPlatformLayoutStore(LifecycleMemoryAccess(initial.preferenceValue))
  val server = LifecycleServer()
  val log = CapturedLog().apply { install() }
  val scheduled = mutableListOf<Runnable>()
  val readiness = AgentPlatformReadiness({ _, task -> scheduled += task }, { server.current })

  fun open() = LifecycleView(store, server, readiness)

  /** What the readiness callback does: `asyncExec { reloadStale(snapshot) }`. */
  fun ready(session: LanguageServerSession? = server.current) = AgentPlatformViews.reloadStale(session)

  val warnings: List<String> get() = log.lines.filter { it.contains("no appReady") }

  fun runTimers() {
    val tasks = scheduled.toList()
    scheduled.clear()
    tasks.forEach(Runnable::run)
  }
}

class AgentPlatformLifecycleTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val newId = AgentPlatformLayout.NEW_WEBVIEW_ID
  val classicId = AgentPlatformLayout.CLASSIC_WEBVIEW_ID

  beforeEach {
    AgentPlatformViews.onDeactivate()
    AgentPlatformViews.onActivate()
  }

  afterEach {
    AgentPlatformViews.onDeactivate()
    AgentPlatformViews.onActivate()
  }

  describe("start, ready, restart and stop") {
    it("loads each view once on start and does not reload them on the ready of the session they show") {
      val f = LifecycleFixture()
      val s1 = LanguageServerSession()
      f.server.current = s1
      val a = f.open()
      val b = f.open()

      f.ready(s1)

      a.host.loads shouldBe listOf(newId)
      b.host.loads shouldBe listOf(newId)
      a.host.displayedSession shouldBeSameInstanceAs s1
    }

    it("reloads every view once onto the new session after a restart, and not again on a repeat") {
      val f = LifecycleFixture()
      val s1 = LanguageServerSession()
      f.server.current = s1
      val a = f.open()
      val b = f.open()

      val s2 = LanguageServerSession()
      f.server.current = s2
      f.ready(s2)
      f.ready(s2)

      a.host.loads shouldBe listOf(newId, newId)
      b.host.loads shouldBe listOf(newId, newId)
      a.host.displayedSession shouldBeSameInstanceAs s2
      b.host.displayedSession shouldBeSameInstanceAs s2
    }

    it("reloads nothing extra for a superseded ready of the old session once the new one is current") {
      val f = LifecycleFixture()
      f.server.current = LanguageServerSession()
      val a = f.open()
      val s2 = LanguageServerSession()
      f.server.current = s2
      f.ready(s2)

      // The S1 callback's asyncExec runs late; it reads the snapshot at run time, which is S2.
      f.ready(f.server.current)

      a.host.loads shouldBe listOf(newId, newId)
    }

    it("reloads nothing while the server is only stopped") {
      val f = LifecycleFixture()
      f.server.current = LanguageServerSession()
      val a = f.open()

      f.server.current = null
      f.ready(null)

      a.host.loads shouldBe listOf(newId)
    }

    it("loads a view opened before the server started once the server becomes ready") {
      val f = LifecycleFixture()
      val a = f.open()
      a.host.displayedSession.shouldBeNull()

      val s1 = LanguageServerSession()
      f.server.current = s1
      f.ready(s1)

      a.host.loads shouldBe listOf(newId, newId)
      a.host.displayedSession shouldBeSameInstanceAs s1
    }

    // Design §5.2, fable F7: a view loaded between handle registration and ready already shows S2.
    it("leaves a view loaded during the start-up window alone") {
      val f = LifecycleFixture()
      val s2 = LanguageServerSession()
      f.server.current = s2
      val a = f.open()

      f.ready(s2)

      a.host.loads shouldBe listOf(newId)
    }

    it("does not reload a view that was closed") {
      val f = LifecycleFixture()
      f.server.current = LanguageServerSession()
      val a = f.open()
      a.close()

      f.server.current = LanguageServerSession()
      f.ready()

      a.host.loads shouldBe listOf(newId)
    }

    it("ends on the latest layout when the layout changes during a restart") {
      val f = LifecycleFixture()
      f.server.current = LanguageServerSession()
      val a = f.open()
      val b = f.open()

      f.server.current = null
      f.server.completesAtOnce = false
      f.store.set(AgentPlatformLayout.CLASSIC)
      val s2 = LanguageServerSession()
      f.server.current = s2
      f.ready(s2)
      f.store.set(AgentPlatformLayout.NEW)
      f.store.set(AgentPlatformLayout.CLASSIC)
      a.host.complete()
      b.host.complete()

      a.host.displayedId shouldBe classicId
      b.host.displayedId shouldBe classicId
      a.host.displayedSession shouldBeSameInstanceAs s2
      a.surface.loadedWebviewId shouldBe classicId
    }
  }

  describe("the appReady diagnostic") {
    it("is armed once per applied page of the new layout and warns when no appReady follows") {
      val f = LifecycleFixture()
      f.server.current = LanguageServerSession()
      f.open()

      f.scheduled shouldHaveSize 1
      f.runTimers()

      f.warnings shouldHaveSize 1
    }

    it("does not warn when appReady arrived for the applied session") {
      val f = LifecycleFixture()
      val s1 = LanguageServerSession()
      f.server.current = s1
      f.open()

      f.readiness.markReady(s1)
      f.runTimers()

      f.warnings.shouldBeEmpty()
    }

    // The classic page sends no appReady to the IDE: arming for it would always warn.
    it("is not armed for the classic layout") {
      val f = LifecycleFixture(AgentPlatformLayout.CLASSIC)
      f.server.current = LanguageServerSession()
      f.open()

      f.scheduled.shouldBeEmpty()
    }

    it("is not armed when the view is switched to classic, and is again when it is switched back") {
      val f = LifecycleFixture()
      f.server.current = LanguageServerSession()
      f.open()
      f.scheduled.clear()

      f.store.set(AgentPlatformLayout.CLASSIC)
      f.scheduled.shouldBeEmpty()

      f.store.set(AgentPlatformLayout.NEW)
      f.scheduled shouldHaveSize 1
    }

    it("is not armed for a page applied after the view was closed") {
      val f = LifecycleFixture()
      f.server.current = LanguageServerSession()
      f.server.completesAtOnce = false
      val a = f.open()
      a.close()

      a.host.complete()

      f.scheduled.shouldBeEmpty()
    }

    it("does not warn for a page whose session was replaced before the timeout") {
      val f = LifecycleFixture()
      f.server.current = LanguageServerSession()
      f.open()

      f.server.current = null
      f.runTimers()

      f.warnings.shouldBeEmpty()
    }
  }
})
