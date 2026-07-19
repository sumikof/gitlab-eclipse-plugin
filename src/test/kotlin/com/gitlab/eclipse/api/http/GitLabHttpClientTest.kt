package com.gitlab.eclipse.api.http

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.net.http.HttpClient

class GitLabHttpClientTest : DescribeSpec({
  val factory = mockk<GitLabHttpClientFactory>()
  val snapA = EgressConfigSnapshot(false, null, null, null, null)
  val snapB = EgressConfigSnapshot(true, null, null, null, null)

  afterEach { clearAllMocks() }

  describe("client lifecycle") {
    it("reuses the built client while the snapshot is unchanged") {
      every { factory.currentSnapshot() } returns snapA
      every { factory.create(snapA) } returns mockk<HttpClient>(relaxed = true)
      val client = GitLabHttpClient(factory)

      client.rebuildIfNeeded(); client.rebuildIfNeeded()

      verify(exactly = 1) { factory.create(snapA) }
    }
    it("rebuilds when the snapshot changes") {
      every { factory.currentSnapshot() } returnsMany listOf(snapA, snapB)
      every { factory.create(any()) } returns mockk<HttpClient>(relaxed = true)
      val client = GitLabHttpClient(factory)

      client.rebuildIfNeeded(); client.rebuildIfNeeded()

      verify(exactly = 1) { factory.create(snapA) }
      verify(exactly = 1) { factory.create(snapB) }
    }
  }
})
