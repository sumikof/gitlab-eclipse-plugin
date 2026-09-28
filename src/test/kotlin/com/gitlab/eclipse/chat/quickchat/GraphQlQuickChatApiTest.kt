package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.GitLabGraphQlClient
import com.gitlab.eclipse.api.GraphQlException
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.CapturingSlot
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.Duration

/**
 * Unit tests for [GraphQlQuickChatApi] (design §11.2): the exact documents and variables each call
 * sends, and how each `data` payload shape decodes. Responses are decoded by a real [Gson] from JSON
 * text so the DTO field names are checked against the wire shape, not against themselves.
 */
class GraphQlQuickChatApiTest : DescribeSpec({
  val graphQl = mockk<GitLabGraphQlClient>()
  val api = GraphQlQuickChatApi(graphQl)
  val gson = Gson()

  val connection = ConnectionSnapshot(
    instanceUrl = "https://gitlab.example.com",
    token = "tok-123",
    authFingerprint = "fingerprint",
    configGeneration = 1L,
  )
  val timeout = Duration.ofSeconds(25)

  class Recorded {
    val query: CapturingSlot<String> = slot()
    val variables: CapturingSlot<Map<String, Any?>> = slot()
    val connection: CapturingSlot<ConnectionSnapshot> = slot()
    val timeout: CapturingSlot<Duration> = slot()
  }

  /** Stubs `execute` to decode [dataJson] (the `data` member) with Gson into the requested type. */
  fun stub(dataJson: String): Recorded {
    val r = Recorded()
    every {
      graphQl.execute(capture(r.query), capture(r.variables), any<Class<Any>>(), capture(r.connection), capture(r.timeout))
    } answers { gson.fromJson(dataJson, thirdArg<Class<Any>>()) }
    return r
  }

  beforeEach { clearMocks(graphQl) }

  describe("constants") {
    it("pins platformOrigin and conversationType") {
      QuickChatApi.PLATFORM_ORIGIN shouldBe "eclipse_plugin"
      QuickChatApi.CONVERSATION_TYPE shouldBe "DUO_QUICK_CHAT"
    }
  }

  describe("ask (M1)") {
    val file = CurrentFile("src/Main.kt", "val x = 1", "above", "below")

    it("sends the M1 document verbatim with every variable on a follow-up question") {
      val r = stub(
        """{"aiAction":{"requestId":"req-1","errors":[],"threadId":"gid://gitlab/Ai::Conversation::Thread/9"}}""",
      )

      val response = api.ask(
        connection,
        "What?",
        file,
        "gid://gitlab/Project/42",
        "gid://gitlab/Ai::Conversation::Thread/9",
        "sub-1",
        timeout,
      )

      r.query.captured shouldBe GraphQlQuickChatApi.ASK_MUTATION
      r.query.captured shouldContain "mutation quickChatAsk("
      r.variables.captured shouldBe mapOf(
        "question" to "What?",
        "clientSubscriptionId" to "sub-1",
        "platformOrigin" to "eclipse_plugin",
        "conversationType" to "DUO_QUICK_CHAT",
        "resourceId" to "gid://gitlab/Project/42",
        "threadId" to "gid://gitlab/Ai::Conversation::Thread/9",
        "currentFile" to mapOf(
          "fileName" to "src/Main.kt",
          "selectedText" to "val x = 1",
          "contentAboveCursor" to "above",
          "contentBelowCursor" to "below",
        ),
      )
      r.connection.captured shouldBeSameInstanceAs connection
      r.timeout.captured shouldBe timeout
      response shouldBe AskResponse("req-1", emptyList(), "gid://gitlab/Ai::Conversation::Thread/9")
    }

    it("omits threadId, resourceId and currentFile keys on a first question outside a project with no selection") {
      val r = stub("""{"aiAction":{"requestId":"req-1","errors":[],"threadId":"t-1"}}""")

      api.ask(connection, "What?", null, null, null, "sub-1", timeout)

      r.variables.captured.containsKey("threadId") shouldBe false
      r.variables.captured.containsKey("resourceId") shouldBe false
      r.variables.captured.containsKey("currentFile") shouldBe false
      r.variables.captured["conversationType"] shouldBe "DUO_QUICK_CHAT"
    }

    it("decodes server errors and a missing threadId (pre-17.10 payload)") {
      stub("""{"aiAction":{"requestId":null,"errors":["Not allowed", null]}}""")

      api.ask(connection, "q", null, null, null, "s", timeout) shouldBe AskResponse(null, listOf("Not allowed"), null)
    }

    it("treats an absent errors array as no errors") {
      stub("""{"aiAction":{"requestId":"r"}}""")

      api.ask(connection, "q", null, null, null, "s", timeout).errors.shouldBeEmpty()
    }

    it("throws JsonSyntaxException with a constant message when the aiAction payload is missing") {
      stub("""{"aiAction":null}""")

      val e = shouldThrow<JsonSyntaxException> { api.ask(connection, "q", null, null, null, "s", timeout) }
      e.message shouldBe GraphQlQuickChatApi.MISSING_PAYLOAD_MESSAGE
    }

    it("propagates client exceptions unchanged") {
      val boom = GitLabApiException(500, "body", "corr")
      every { graphQl.execute(any(), any(), any<Class<Any>>(), any(), any()) } throws boom

      shouldThrow<GitLabApiException> { api.ask(connection, "q", null, null, null, "s", timeout) } shouldBeSameInstanceAs boom
    }
  }

  describe("clear (M2)") {
    it("sends the M2 document with only question, threadId and platformOrigin") {
      val r = stub("""{"aiAction":{"requestId":"req-2","errors":[]}}""")

      val response = api.clear(connection, "/reset", "t-1", timeout)

      r.query.captured shouldBe GraphQlQuickChatApi.CLEAR_MUTATION
      r.query.captured shouldContain "mutation quickChatClear("
      r.variables.captured shouldBe mapOf(
        "question" to "/reset",
        "threadId" to "t-1",
        "platformOrigin" to "eclipse_plugin",
      )
      r.timeout.captured shouldBe timeout
      response shouldBe ClearResponse("req-2", emptyList())
    }

    it("decodes errors and tolerates absent fields") {
      stub("""{"aiAction":{"errors":["nope"]}}""")

      api.clear(connection, "/clear", "t-1", timeout) shouldBe ClearResponse(null, listOf("nope"))
    }

    it("throws JsonSyntaxException when the aiAction payload is missing") {
      stub("""{}""")

      shouldThrow<JsonSyntaxException> { api.clear(connection, "/clear", "t-1", timeout) }
    }

    it("propagates GraphQlException unchanged") {
      val boom = GraphQlException(true, listOf("x"), null)
      every { graphQl.execute(any(), any(), any<Class<Any>>(), any(), any()) } throws boom

      shouldThrow<GraphQlException> { api.clear(connection, "/clear", "t", timeout) } shouldBeSameInstanceAs boom
    }
  }

  describe("version (Q1a)") {
    it("sends the version document with no variables and returns the version") {
      val r = stub("""{"metadata":{"version":"17.10.0-ee"}}""")

      api.version(connection, timeout) shouldBe "17.10.0-ee"
      r.query.captured shouldBe GraphQlQuickChatApi.VERSION_QUERY
      r.query.captured shouldContain "query quickChatVersion"
      r.variables.captured shouldBe emptyMap()
      r.timeout.captured shouldBe timeout
    }

    it("returns null when metadata is null") {
      stub("""{"metadata":null}""")
      api.version(connection, timeout) shouldBe null
    }

    it("returns null when version is absent") {
      stub("""{"metadata":{}}""")
      api.version(connection, timeout) shouldBe null
    }
  }

  describe("project (Q1b)") {
    it("sends the project document with fullPath and decodes id and duoFeaturesEnabled") {
      val r = stub("""{"project":{"id":"gid://gitlab/Project/42","duoFeaturesEnabled":false}}""")

      api.project(connection, "group/sub/proj", timeout) shouldBe ProjectInfo("gid://gitlab/Project/42", false)
      r.query.captured shouldBe GraphQlQuickChatApi.PROJECT_QUERY
      r.query.captured shouldContain "query quickChatProject("
      r.variables.captured shouldBe mapOf("fullPath" to "group/sub/proj")
      r.timeout.captured shouldBe timeout
    }

    it("keeps a null duoFeaturesEnabled as null") {
      stub("""{"project":{"id":"gid://gitlab/Project/42","duoFeaturesEnabled":null}}""")
      api.project(connection, "g/p", timeout) shouldBe ProjectInfo("gid://gitlab/Project/42", null)
    }

    it("returns null when the project is not found or not visible") {
      stub("""{"project":null}""")
      api.project(connection, "g/p", timeout) shouldBe null
    }

    it("returns null when the project carries no id (cannot build a resourceId)") {
      stub("""{"project":{"duoFeaturesEnabled":true}}""")
      api.project(connection, "g/p", timeout) shouldBe null
    }
  }

  describe("messages (Q2)") {
    it("sends the polling document with requestIds, ASSISTANT role and threadId") {
      val r = stub(
        """{"aiMessages":{"nodes":[
          {"requestId":"req-1","role":"ASSISTANT","content":"Hi","errors":[],"timestamp":"2026-09-28T00:00:00Z"}
        ]}}""",
      )

      val nodes = api.messages(connection, "req-1", "t-1", timeout)

      r.query.captured shouldBe GraphQlQuickChatApi.MESSAGES_QUERY
      r.query.captured shouldContain "query quickChatMessages("
      r.variables.captured shouldBe mapOf(
        "requestIds" to listOf("req-1"),
        "roles" to listOf("ASSISTANT"),
        "threadId" to "t-1",
      )
      r.timeout.captured shouldBe timeout
      nodes shouldBe listOf(AiMessageNode("req-1", "ASSISTANT", "Hi", emptyList(), "2026-09-28T00:00:00Z"))
    }

    it("keeps null fields and an absent errors array as null, and drops null nodes and null error entries") {
      stub("""{"aiMessages":{"nodes":[null, {"role":"ASSISTANT","errors":["e1", null]}, {"requestId":"r"}]}}""")

      api.messages(connection, "r", "t", timeout) shouldBe listOf(
        AiMessageNode(null, "ASSISTANT", null, listOf("e1"), null),
        AiMessageNode("r", null, null, null, null),
      )
    }

    it("returns an empty list when aiMessages or nodes is absent") {
      stub("""{"aiMessages":null}""")
      api.messages(connection, "r", "t", timeout).shouldBeEmpty()

      stub("""{"aiMessages":{"nodes":null}}""")
      api.messages(connection, "r", "t", timeout).shouldBeEmpty()
    }
  }

  describe("GraphQL documents (design §11.2 verbatim)") {
    it("ask selects requestId errors threadId and passes every input field") {
      val q = GraphQlQuickChatApi.ASK_MUTATION
      q shouldContain "chat: { resourceId: \$resourceId, content: \$question, currentFile: \$currentFile }"
      q shouldContain "conversationType: \$conversationType"
      q shouldContain "threadId: \$threadId"
      q shouldContain "}) { requestId errors threadId }"
    }

    it("clear sends only content, threadId and platformOrigin") {
      GraphQlQuickChatApi.CLEAR_MUTATION shouldContain
        "aiAction(input: { chat: { content: \$question }, threadId: \$threadId, platformOrigin: \$platformOrigin })"
    }

    it("project and version are separate documents") {
      GraphQlQuickChatApi.VERSION_QUERY.trim() shouldBe "query quickChatVersion { metadata { version } }"
      GraphQlQuickChatApi.PROJECT_QUERY.trim() shouldBe
        "query quickChatProject(\$fullPath: ID!) { project(fullPath: \$fullPath) { id duoFeaturesEnabled } }"
    }

    it("messages selects the node fields the poller needs") {
      GraphQlQuickChatApi.MESSAGES_QUERY shouldContain "nodes { requestId role content errors timestamp }"
    }
  }
})
