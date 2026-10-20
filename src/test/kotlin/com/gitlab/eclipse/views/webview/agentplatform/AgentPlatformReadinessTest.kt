package com.gitlab.eclipse.views.webview.agentplatform

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.preferences.storage.CapturedLog
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

private const val WARNING = "Agent Platform: no appReady within 10 s"

/**
 * The readiness diagnostic of design §5.4: a page applied for a session that sends no `appReady`
 * within 10 s earns one WARN, and nothing else does.
 */
class AgentPlatformReadinessTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  class Fixture {
    val scheduled = mutableListOf<Pair<Long, Runnable>>()
    var current: LanguageServerSession? = null
    val log = CapturedLog().apply { install() }
    val readiness = AgentPlatformReadiness({ delay, task -> scheduled += delay to task }, { current })

    fun fireAll() = scheduled.toList().forEach { it.second.run() }

    val warnings: List<String> get() = log.lines.filter { it.contains("appReady") }
  }

  describe("expect") {
    it("schedules one check 10 000 ms out") {
      val f = Fixture()
      val s = LanguageServerSession().also { f.current = it }

      f.readiness.expect(s)

      f.scheduled.map { it.first } shouldContainExactly listOf(10_000L)
    }

    it("stays silent when appReady arrives for the session before the deadline") {
      val f = Fixture()
      val s = LanguageServerSession().also { f.current = it }

      f.readiness.expect(s)
      f.readiness.markReady(s)
      f.fireAll()

      f.warnings shouldHaveSize 0
    }

    it("warns once, without any URL, when no appReady arrives") {
      val f = Fixture()
      val s = LanguageServerSession().also { f.current = it }

      f.readiness.expect(s)
      f.fireAll()

      f.warnings shouldContainExactly listOf(WARNING)
      f.log.lines.any { it.contains("http") || it.contains("://") } shouldBe false
    }

    it("warns when the only appReady came from another session") {
      val f = Fixture()
      val s = LanguageServerSession().also { f.current = it }

      f.readiness.expect(s)
      f.readiness.markReady(LanguageServerSession())
      f.fireAll()

      f.warnings shouldContainExactly listOf(WARNING)
    }

    it("stays silent when the session was replaced before the deadline") {
      val f = Fixture()
      val s = LanguageServerSession().also { f.current = it }

      f.readiness.expect(s)
      f.current = LanguageServerSession()
      f.fireAll()

      f.warnings shouldHaveSize 0
    }

    it("stays silent when the language server stopped before the deadline") {
      val f = Fixture()
      val s = LanguageServerSession().also { f.current = it }

      f.readiness.expect(s)
      f.current = null
      f.fireAll()

      f.warnings shouldHaveSize 0
    }

    it("checks only the latest expectation") {
      val f = Fixture()
      val s = LanguageServerSession().also { f.current = it }

      f.readiness.expect(s)
      f.readiness.expect(s)
      f.fireAll()

      f.warnings shouldContainExactly listOf(WARNING)
    }

    // A reload within one session shows a new page; the appReady of the page it replaced says
    // nothing about whether the new one completed its handshake.
    it("does not count an appReady that preceded the latest expectation") {
      val f = Fixture()
      val s = LanguageServerSession().also { f.current = it }

      f.readiness.expect(s)
      f.readiness.markReady(s)
      f.readiness.expect(s)
      f.fireAll()

      f.warnings shouldContainExactly listOf(WARNING)
    }
  }
})
