package com.gitlab.eclipse.mergerequests.discussions

import com.gitlab.eclipse.api.DiscussionMutationException
import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.GitLabApiTimeoutException
import com.gitlab.eclipse.api.GraphQlException
import com.gitlab.eclipse.api.NoteChangedException
import com.google.gson.JsonSyntaxException
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException
import java.net.http.HttpTimeoutException

class DiscussionWriteOutcomeTest : DescribeSpec({
  describe("classifyWriteFailure") {
    it("classifies GitLabApiException(403) as Definite") {
      val cause = GitLabApiException(403, "forbidden", null)
      classifyWriteFailure(cause).shouldBeInstanceOf<DiscussionWriteOutcome.Definite>()
    }

    it("classifies GitLabApiException(400) as Definite") {
      val cause = GitLabApiException(400, "bad request", null)
      classifyWriteFailure(cause).shouldBeInstanceOf<DiscussionWriteOutcome.Definite>()
    }

    it("classifies GitLabApiException(499) as Definite") {
      val cause = GitLabApiException(499, "client limit", null)
      classifyWriteFailure(cause).shouldBeInstanceOf<DiscussionWriteOutcome.Definite>()
    }

    it("classifies GitLabApiException(399) as Ambiguous") {
      val cause = GitLabApiException(399, "odd", null)
      classifyWriteFailure(cause).shouldBeInstanceOf<DiscussionWriteOutcome.Ambiguous>()
    }

    it("classifies GitLabApiException(502) as Ambiguous") {
      val cause = GitLabApiException(502, "bad gateway", null)
      classifyWriteFailure(cause).shouldBeInstanceOf<DiscussionWriteOutcome.Ambiguous>()
    }

    it("classifies GitLabApiException(500) as Ambiguous") {
      val cause = GitLabApiException(500, "boom", null)
      classifyWriteFailure(cause).shouldBeInstanceOf<DiscussionWriteOutcome.Ambiguous>()
    }

    it("classifies GraphQlException with hasDataKey = false as Definite") {
      val cause = GraphQlException(hasDataKey = false, messages = listOf("bad var"))
      classifyWriteFailure(cause).shouldBeInstanceOf<DiscussionWriteOutcome.Definite>()
    }

    it("classifies GraphQlException with hasDataKey = true as Ambiguous") {
      val cause = GraphQlException(hasDataKey = true, messages = listOf("resolver failed"))
      classifyWriteFailure(cause).shouldBeInstanceOf<DiscussionWriteOutcome.Ambiguous>()
    }

    it("classifies DiscussionMutationException as Definite") {
      val cause = DiscussionMutationException(listOf("Note body is too long"))
      classifyWriteFailure(cause).shouldBeInstanceOf<DiscussionWriteOutcome.Definite>()
    }

    it("classifies NoteChangedException as Definite") {
      val cause = NoteChangedException()
      classifyWriteFailure(cause).shouldBeInstanceOf<DiscussionWriteOutcome.Definite>()
    }

    it("classifies HttpTimeoutException as Ambiguous") {
      val cause = HttpTimeoutException("timed out")
      classifyWriteFailure(cause).shouldBeInstanceOf<DiscussionWriteOutcome.Ambiguous>()
    }

    it("classifies GitLabApiTimeoutException as Ambiguous") {
      val cause = GitLabApiTimeoutException(1)
      classifyWriteFailure(cause).shouldBeInstanceOf<DiscussionWriteOutcome.Ambiguous>()
    }

    it("classifies IOException as Ambiguous") {
      val cause = IOException("connection reset")
      classifyWriteFailure(cause).shouldBeInstanceOf<DiscussionWriteOutcome.Ambiguous>()
    }

    // Load-bearing for the write path, not just for malformed JSON: DiscussionWriteService throws
    // JsonSyntaxException when a mutation's payload object is absent, precisely so that case lands
    // here as Ambiguous. A missing payload does not prove the mutation never ran, so [Retry] — the
    // Definite affordance — could post the comment twice.
    it("classifies JsonSyntaxException as Ambiguous") {
      val cause = JsonSyntaxException("malformed")
      classifyWriteFailure(cause).shouldBeInstanceOf<DiscussionWriteOutcome.Ambiguous>()
    }

    it("classifies an unknown RuntimeException as Ambiguous") {
      val cause = RuntimeException("unknown")
      classifyWriteFailure(cause).shouldBeInstanceOf<DiscussionWriteOutcome.Ambiguous>()
    }

    it("preserves the cause unchanged on both Definite and Ambiguous") {
      val definiteCause = GitLabApiException(403, "forbidden", null)
      val definite = classifyWriteFailure(definiteCause) as DiscussionWriteOutcome.Definite
      definite.cause shouldBe definiteCause

      val ambiguousCause = GitLabApiException(500, "boom", null)
      val ambiguous = classifyWriteFailure(ambiguousCause) as DiscussionWriteOutcome.Ambiguous
      ambiguous.cause shouldBe ambiguousCause
    }

    it("never leaks the mutation error message text into the exception message") {
      val cause = DiscussionMutationException(listOf("body was 'SECRET-MARKER'"))
      cause.message shouldNotContain "SECRET-MARKER"
    }
  }
})
