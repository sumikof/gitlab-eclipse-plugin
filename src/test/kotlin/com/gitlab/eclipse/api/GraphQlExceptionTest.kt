package com.gitlab.eclipse.api

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class GraphQlExceptionTest : DescribeSpec({
  describe("GraphQlException") {
    it("retains hasDataKey = true") {
      val e = GraphQlException(hasDataKey = true, messages = emptyList())
      e.hasDataKey shouldBe true
    }

    it("retains hasDataKey = false") {
      val e = GraphQlException(hasDataKey = false, messages = emptyList())
      e.hasDataKey shouldBe false
    }

    it("retains messages as given, in order") {
      val e = GraphQlException(hasDataKey = true, messages = listOf("first", "second", "third"))
      e.messages shouldBe listOf("first", "second", "third")
    }

    it("retains correlationId when supplied") {
      val e = GraphQlException(hasDataKey = true, messages = emptyList(), correlationId = "req-123")
      e.correlationId shouldBe "req-123"
    }

    it("defaults correlationId to null when omitted") {
      val e = GraphQlException(hasDataKey = true, messages = emptyList())
      e.correlationId shouldBe null
    }

    it("joins several messages with '; ' after the prefix") {
      val e = GraphQlException(hasDataKey = true, messages = listOf("bad syntax", "unauthorized"))
      e.message shouldBe "GraphQL request failed: bad syntax; unauthorized"
    }

    it("has no separator for a single message") {
      val e = GraphQlException(hasDataKey = true, messages = listOf("bad syntax"))
      e.message shouldBe "GraphQL request failed: bad syntax"
    }

    it("produces exactly 'GraphQL request failed' for empty messages") {
      val e = GraphQlException(hasDataKey = true, messages = emptyList())
      e.message shouldBe "GraphQL request failed"
    }

    it("is a RuntimeException") {
      val e = GraphQlException(hasDataKey = true, messages = emptyList())
      e.shouldBeInstanceOf<RuntimeException>()
    }
  }
})
