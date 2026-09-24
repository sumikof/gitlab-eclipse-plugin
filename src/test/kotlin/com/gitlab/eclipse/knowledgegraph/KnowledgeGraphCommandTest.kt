package com.gitlab.eclipse.knowledgegraph

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.GitLabLanguageServer
import com.gitlab.eclipse.lsp.LanguageServerHandle
import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.lsp.plugins.messages.ExtensionToPluginRequest
import com.google.gson.Gson
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.Platform
import org.osgi.framework.Bundle
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private const val GRAPH_URL = "http://localhost:4321"
private const val OTHER_URL = "http://localhost:9999"

/** A null where Kotlin declares none, as a Java proxy can return; an unchecked cast of null does not throw. */
@Suppress("UNCHECKED_CAST")
private fun <T> javaNull(): T = null as T

/** Installs a log that records every message handed to it. */
private fun captureLog(): List<String> {
  val recorded = java.util.Collections.synchronizedList(mutableListOf<String>())
  val log = mockk<ILog>()
  val message = slot<String>()
  val status = slot<IStatus>()
  every { log.error(capture(message)) } answers { recorded += message.captured }
  every { log.error(capture(message), any()) } answers { recorded += message.captured }
  every { log.warn(capture(message)) } answers { recorded += message.captured }
  every { log.warn(capture(message), any()) } answers { recorded += message.captured }
  every { log.info(capture(message)) } answers { recorded += message.captured }
  every { log.info(capture(message), any()) } answers { recorded += message.captured }
  every { log.log(capture(status)) } answers { recorded += status.captured.message }
  every { Platform.getLog(any<Bundle>()) } returns log
  every { Platform.getLog(any<Class<*>>()) } returns log
  return recorded
}

/**
 * One command over a fake connection. [current] is what the snapshot supplier answers at every read, so a
 * test can swap it between the request and its completion (a reconnect). [hops] records what is handed to
 * the UI thread; nothing runs until the test runs it.
 */
private class Fixture(timeoutMillis: Long = 10_000L) {
  val session = LanguageServerSession()
  val proxy = mockk<GitLabLanguageServer>()
  val handle = LanguageServerHandle(proxy, session, 0L)

  @Volatile var current: LanguageServerHandle? = handle
  val response = CompletableFuture<Any?>()
  val requests = mutableListOf<ExtensionToPluginRequest>()
  val hops = LinkedBlockingQueue<Runnable>()
  var opened = 0
  var hopFailure: Throwable? = null

  init {
    every { proxy.pluginRequest(capture(requests)) } returns response
  }

  val command = KnowledgeGraphCommand(
    currentSnapshot = { current },
    onUiThread = { runnable: Runnable ->
      hopFailure?.let { throw it }
      hops.add(runnable)
    },
    timeoutMillis = timeoutMillis,
  )

  fun run() = command.run { opened++ }

  /** Waits for the completion to hand its runnable to the UI thread, then runs it there. */
  fun runHop() {
    val hop = hops.poll(5, TimeUnit.SECONDS)
    hop.shouldNotBeNull()
    hop.run()
  }
}

/** Plan §9.3 steps 2–6; A8, A14, A20, A25, A26; controller ruling R2. */
class KnowledgeGraphCommandTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  describe("without a connection") {
    // Ruling R2: the tab shows its own "waiting for the language server" page.
    it("opens the tab at once and sends nothing") {
      val fixture = Fixture()
      fixture.current = null

      fixture.run()

      fixture.opened shouldBe 1
      verify(exactly = 0) { fixture.proxy.pluginRequest(any()) }
      fixture.hops.shouldBeEmpty()
    }
  }

  describe("with an address already held for the current connection (A26)") {
    it("opens the tab at once and asks nothing") {
      val fixture = Fixture()
      KnowledgeGraphState.record(GRAPH_URL, fixture.session, fixture.session)

      fixture.run()

      fixture.opened shouldBe 1
      verify(exactly = 0) { fixture.proxy.pluginRequest(any()) }
    }

    it("still asks when the address held is another connection's") {
      val fixture = Fixture()
      val old = LanguageServerSession()
      KnowledgeGraphState.record(OTHER_URL, old, old)

      fixture.run()

      fixture.requests.size shouldBe 1
    }
  }

  describe("with nothing held (A26 main path)") {
    it("sends getUrl once to the knowledge-graph plugin, without a payload") {
      val fixture = Fixture()

      fixture.run()

      fixture.requests shouldContainExactly listOf(ExtensionToPluginRequest("knowledge-graph", "getUrl"))
    }

    it("records the answer for the connection, opens the tab through the UI hop, and asks no more") {
      val fixture = Fixture()

      fixture.run()
      fixture.response.complete(mapOf("url" to GRAPH_URL))
      fixture.runHop()

      KnowledgeGraphState.urlFor(fixture.session) shouldBe GRAPH_URL
      fixture.opened shouldBe 1

      fixture.run()

      fixture.requests.size shouldBe 1
      fixture.opened shouldBe 2
    }

    // The collision of plan §3: the real answer arrives as a Gson LinkedTreeMap.
    it("records the answer when it arrives as a Gson map (A20)") {
      val fixture = Fixture()

      fixture.run()
      fixture.response.complete(Gson().fromJson("""{"url":"$GRAPH_URL"}""", Any::class.java))
      fixture.runHop()

      KnowledgeGraphState.urlFor(fixture.session) shouldBe GRAPH_URL
      fixture.opened shouldBe 1
    }

    it("returns before the answer arrives and opens nothing until it does") {
      val fixture = Fixture()

      fixture.run()

      fixture.response.isDone shouldBe false
      fixture.opened shouldBe 0
      fixture.hops.shouldBeEmpty()
    }
  }

  describe("an answer without an address (A8, A20)") {
    val unusable = mapOf<String, () -> Any?>(
      "an empty url" to { mapOf("url" to "") },
      "a blank url" to { mapOf("url" to "  ") },
      "an empty object" to { emptyMap<String, Any?>() },
      "a Gson map with no url (gkg not running)" to { Gson().fromJson("{}", Any::class.java) },
      "a non-string url" to { mapOf("url" to 42) },
      "a non-map" to { listOf(GRAPH_URL) },
      "null" to { null },
    )
    unusable.forEach { (name, answer) ->
      it("records nothing for $name and still opens the tab") {
        val fixture = Fixture()

        fixture.run()
        fixture.response.complete(answer())
        fixture.runHop()

        KnowledgeGraphState.urlFor(fixture.session).shouldBeNull()
        fixture.opened shouldBe 1
      }
    }

    it("records nothing when the request fails and still opens the tab") {
      val fixture = Fixture()

      fixture.run()
      fixture.response.completeExceptionally(IllegalStateException("boom"))
      fixture.runHop()

      KnowledgeGraphState.urlFor(fixture.session).shouldBeNull()
      fixture.opened shouldBe 1
    }

    it("gives up after the timeout, records nothing and still opens the tab") {
      val fixture = Fixture(timeoutMillis = 20L)

      fixture.run()
      fixture.runHop()

      fixture.response.isCompletedExceptionally shouldBe true
      KnowledgeGraphState.urlFor(fixture.session).shouldBeNull()
      fixture.opened shouldBe 1
      fixture.requests.size shouldBe 1
    }

    it("opens the tab when the proxy throws instead of answering") {
      val fixture = Fixture()
      every { fixture.proxy.pluginRequest(any()) } throws IllegalStateException("closed")

      fixture.run()

      fixture.opened shouldBe 1
      fixture.hops.shouldBeEmpty()
    }

    it("opens the tab when the proxy answers with no future") {
      val fixture = Fixture()
      every { fixture.proxy.pluginRequest(any()) } returns javaNull()

      fixture.run()

      fixture.opened shouldBe 1
    }
  }

  describe("a reconnect between the request and its answer (A14, A25 i)") {
    it("does not record the old connection's answer") {
      val fixture = Fixture()

      fixture.run()
      val next = LanguageServerHandle(mockk(), LanguageServerSession(), 1L)
      fixture.current = next
      fixture.response.complete(mapOf("url" to GRAPH_URL))
      fixture.runHop()

      KnowledgeGraphState.urlFor(fixture.session).shouldBeNull()
      KnowledgeGraphState.urlFor(next.session).shouldBeNull()
      fixture.opened shouldBe 1
    }

    it("does not overwrite what the new connection reported") {
      val fixture = Fixture()

      fixture.run()
      val next = LanguageServerHandle(mockk(), LanguageServerSession(), 1L)
      fixture.current = next
      KnowledgeGraphState.record(OTHER_URL, next.session, next.session)
      fixture.response.complete(mapOf("url" to GRAPH_URL))
      fixture.runHop()

      KnowledgeGraphState.urlFor(next.session) shouldBe OTHER_URL
    }

    it("records nothing when the connection is gone by then") {
      val fixture = Fixture()

      fixture.run()
      fixture.current = null
      fixture.response.complete(mapOf("url" to GRAPH_URL))
      fixture.runHop()

      KnowledgeGraphState.urlFor(fixture.session).shouldBeNull()
    }
  }

  describe("a failing hop to the UI thread") {
    it("does not escape the completion and logs only the exception type") {
      val log = captureLog()
      val fixture = Fixture()
      fixture.hopFailure = IllegalStateException("Device is disposed $GRAPH_URL")

      fixture.run()
      fixture.response.complete(mapOf("url" to GRAPH_URL))

      fixture.opened shouldBe 0
      log.size shouldBe 1
      log.single() shouldNotContain "Device is disposed"
      log.single() shouldNotContain GRAPH_URL
      log.single().contains("IllegalStateException") shouldBe true
    }
  }

  describe("log hygiene (§15)") {
    it("never logs the address, on any path") {
      val log = captureLog()

      Fixture().apply {
        run()
        response.complete(mapOf("url" to GRAPH_URL))
        runHop()
      }
      Fixture().apply {
        run()
        response.completeExceptionally(IllegalStateException(GRAPH_URL))
        runHop()
      }
      Fixture(timeoutMillis = 20L).apply {
        run()
        runHop()
      }
      Fixture().apply {
        every { proxy.pluginRequest(any()) } throws IllegalStateException(GRAPH_URL)
        run()
      }

      log.forEach {
        it shouldNotContain GRAPH_URL
        it shouldNotContain "localhost"
      }
    }

    it("does not log a missing gkg") {
      val log = captureLog()

      Fixture().apply {
        run()
        response.complete(mapOf("url" to null))
        runHop()
      }

      log.shouldBeEmpty()
    }
  }
})
