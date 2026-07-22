package com.gitlab.eclipse.api.http

import io.kotest.core.spec.style.DescribeSpec
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
      val reused = mockk<HttpClient>(relaxed = true)
      every { factory.create(snapA) } returns reused
      val client = GitLabHttpClient(factory)

      client.rebuildIfNeeded()
      client.rebuildIfNeeded()

      verify(exactly = 1) { factory.create(snapA) }
      verify(exactly = 0) { reused.shutdown() }
    }
    it("rebuilds when the snapshot changes") {
      every { factory.currentSnapshot() } returnsMany listOf(snapA, snapB)
      every { factory.create(any()) } returns mockk<HttpClient>(relaxed = true)
      val client = GitLabHttpClient(factory)

      client.rebuildIfNeeded()
      client.rebuildIfNeeded()

      verify(exactly = 1) { factory.create(snapA) }
      verify(exactly = 1) { factory.create(snapB) }
    }
    it("shuts down the old client when rebuilding for a new snapshot") {
      every { factory.currentSnapshot() } returnsMany listOf(snapA, snapB)
      val oldClient = mockk<HttpClient>(relaxed = true)
      val newClient = mockk<HttpClient>(relaxed = true)
      every { factory.create(snapA) } returns oldClient
      every { factory.create(snapB) } returns newClient
      val client = GitLabHttpClient(factory)

      client.rebuildIfNeeded()
      client.rebuildIfNeeded()

      verify(exactly = 1) { oldClient.shutdown() }
      verify(exactly = 0) { newClient.shutdown() }
    }
    it("close() shuts down the current client and clears cached state") {
      every { factory.currentSnapshot() } returns snapA
      val firstClient = mockk<HttpClient>(relaxed = true)
      val secondClient = mockk<HttpClient>(relaxed = true)
      every { factory.create(snapA) } returnsMany listOf(firstClient, secondClient)
      val client = GitLabHttpClient(factory)

      client.rebuildIfNeeded()
      client.close()

      verify(exactly = 1) { firstClient.shutdown() }

      client.rebuildIfNeeded()

      verify(exactly = 2) { factory.create(snapA) }
    }
  }
})
