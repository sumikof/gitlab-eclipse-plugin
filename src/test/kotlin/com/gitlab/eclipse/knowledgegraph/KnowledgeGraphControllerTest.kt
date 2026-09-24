package com.gitlab.eclipse.knowledgegraph

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.GitLabLanguageServer
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.LanguageServerHandle
import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.lsp.plugins.PluginMessageService
import com.gitlab.eclipse.lsp.plugins.PluginRegistry
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageRoute
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageType
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.Platform
import org.osgi.framework.Bundle

private const val URL_OLD = "http://localhost:1111"
private const val URL_NEW = "http://localhost:2222"

/**
 * Plan §8.2 (`KnowledgeGraphController`), §15 (tolerant `ready` DTO), A14 / A25. The state is a
 * global object, so every case uses fresh [LanguageServerSession]s (see [KnowledgeGraphStateTest]).
 */
class KnowledgeGraphControllerTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  fun wrapperWithCurrent(session: LanguageServerSession?): GitLabLanguageServerWrapper = mockk {
    every { currentSnapshot } returns session?.let { LanguageServerHandle(mockk<GitLabLanguageServer>(), it, 0L) }
  }

  describe("ready") {
    it("現行 session からの ready は URL を記録する") {
      val current = LanguageServerSession()
      val controller = KnowledgeGraphController(wrapperWithCurrent(current))

      controller.ready(KnowledgeGraphReady(URL_NEW), current)

      KnowledgeGraphState.urlFor(current) shouldBe URL_NEW
    }

    it("新 session の URL 記録後、旧 session から遅れて届いた ready は上書きしない (A14 / A25 i)") {
      val old = LanguageServerSession()
      val new = LanguageServerSession()
      val controller = KnowledgeGraphController(wrapperWithCurrent(new))

      controller.ready(KnowledgeGraphReady(URL_NEW), new)
      controller.ready(KnowledgeGraphReady(URL_OLD), old)

      KnowledgeGraphState.urlFor(new) shouldBe URL_NEW
      KnowledgeGraphState.urlFor(old).shouldBeNull()
    }

    it("現行 snapshot が null なら何も記録しない (A25 ii)") {
      val sender = LanguageServerSession()
      val controller = KnowledgeGraphController(wrapperWithCurrent(null))

      controller.ready(KnowledgeGraphReady(URL_NEW), sender)

      KnowledgeGraphState.urlFor(sender).shouldBeNull()
    }

    it("url が文字列でない・無い payload は記録せず投げない") {
      val current = LanguageServerSession()
      val controller = KnowledgeGraphController(wrapperWithCurrent(current))

      controller.ready(KnowledgeGraphReady(42), current)
      controller.ready(KnowledgeGraphReady(), current)
      controller.ready(KnowledgeGraphReady(mapOf("x" to 1)), current)
      controller.ready(null, current)

      KnowledgeGraphState.urlFor(current).shouldBeNull()
    }

    it("通知 1 回につき snapshot を 1 回だけ読む") {
      val current = LanguageServerSession()
      val wrapper = wrapperWithCurrent(current)

      KnowledgeGraphController(wrapper).ready(KnowledgeGraphReady(URL_NEW), current)

      verify(exactly = 1) { wrapper.currentSnapshot }
    }
  }

  describe("PluginRegistry / PluginMessageService 経由") {
    val route =
      PluginMessageRoute(pluginId = "knowledge-graph", type = PluginMessageType.NOTIFICATION, method = "ready")

    fun serviceFor(wrapper: GitLabLanguageServerWrapper) =
      PluginMessageService(PluginRegistry(listOf(KnowledgeGraphController(wrapper))))

    it("knowledge-graph / ready に配線され、送信元 session がハンドラに届く") {
      val current = LanguageServerSession()
      val service = serviceFor(wrapperWithCurrent(current))

      service.dispatch(route, mapOf("url" to URL_NEW), current).get()

      KnowledgeGraphState.urlFor(current) shouldBe URL_NEW
    }

    it("送信元が旧 session なら配線経由でも記録しない") {
      val old = LanguageServerSession()
      val new = LanguageServerSession()
      val service = serviceFor(wrapperWithCurrent(new))

      service.dispatch(route, mapOf("url" to URL_OLD), old).get()

      KnowledgeGraphState.urlFor(new).shouldBeNull()
      KnowledgeGraphState.urlFor(old).shouldBeNull()
    }

    it("形の崩れた payload でも WARN / ERROR を出さず payload をログに残さない (§15)") {
      val log = mockk<ILog>(relaxUnitFun = true)
      every { Platform.getLog(any<Bundle>()) } returns log
      every { Platform.getLog(any<Class<*>>()) } returns log
      val current = LanguageServerSession()
      val service = serviceFor(wrapperWithCurrent(current))

      service.dispatch(route, mapOf("url" to mapOf("a" to 1)), current).get()
      service.dispatch(route, mapOf("url" to 42), current).get()
      service.dispatch(route, mapOf<String, Any>(), current).get()

      KnowledgeGraphState.urlFor(current).shouldBeNull()
      verify(exactly = 0) { log.warn(any<String>()) }
      verify(exactly = 0) { log.warn(any<String>(), any()) }
      verify(exactly = 0) { log.error(any<String>()) }
      verify(exactly = 0) { log.error(any<String>(), any()) }
    }
  }
})
