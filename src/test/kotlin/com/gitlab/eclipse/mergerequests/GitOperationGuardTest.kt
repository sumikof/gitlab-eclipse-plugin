package com.gitlab.eclipse.mergerequests
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch

class GitOperationGuardTest : StringSpec({
  "rejects re-entry on the same key while running" {
    val guard = GitOperationGuard()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val t = Thread {
      guard.withRepo("/r/.git") {
        entered.countDown()
        release.await()
        "ok"
      }
    }
    t.start()
    entered.await()
    guard.withRepo("/r/.git") { "second" } shouldBe null // rejected while first runs
    release.countDown()
    t.join()
    guard.withRepo("/r/.git") { "third" } shouldBe "third" // reacquirable after completion
  }
  "different keys run independently" {
    val guard = GitOperationGuard()
    guard.withRepo("/a/.git") { 1 } shouldBe 1
    guard.withRepo("/b/.git") { 2 } shouldBe 2
  }
})
