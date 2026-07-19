package com.gitlab.eclipse.views.issues

import com.gitlab.eclipse.api.model.GitLabIssue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class IssueListPresenterTest : DescribeSpec({
  val issue = GitLabIssue(1, 1, "A", "https://x/1", "opened", null)

  describe("onResult") {
    it("applies the result of the current generation") {
      val applied = mutableListOf<GitLabIssue>()
      val state = ViewRefreshState()
      val gen = state.begin()
      val local = IssueListPresenter(state, { false }, { applied.clear(); applied.addAll(it) }, {})
      local.onResult(gen, Result.success(listOf(issue)))
      applied shouldBe listOf(issue)
    }

    it("ignores an out-of-order (stale) result") {
      val applied = mutableListOf<GitLabIssue>()
      val state = ViewRefreshState()
      val presenter = IssueListPresenter(state, { false }, { applied.clear(); applied.addAll(it) }, {})
      val genOld = state.begin()
      state.begin() // a newer refresh started
      presenter.onResult(genOld, Result.success(listOf(issue)))
      applied shouldBe emptyList()
    }

    it("does nothing when the view is disposed") {
      val applied = mutableListOf<GitLabIssue>()
      val errors = mutableListOf<Throwable>()
      val state = ViewRefreshState()
      val presenter = IssueListPresenter(state, { true }, { applied.addAll(it) }, { errors.add(it) })
      val gen = state.begin()
      presenter.onResult(gen, Result.success(listOf(issue)))
      presenter.onResult(gen, Result.failure(RuntimeException("x")))
      applied shouldBe emptyList()
      errors shouldBe emptyList()
    }

    it("routes a failure to showError for the current generation") {
      val errors = mutableListOf<Throwable>()
      val state = ViewRefreshState()
      val presenter = IssueListPresenter(state, { false }, {}, { errors.add(it) })
      val gen = state.begin()
      val boom = RuntimeException("boom")
      presenter.onResult(gen, Result.failure(boom))
      errors shouldBe listOf(boom)
    }
  }
})
