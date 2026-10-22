package com.gitlab.eclipse.currentbranch

import com.gitlab.eclipse.views.sidebar.autorefresh.Registration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/** A display whose Activate/Deactivate filter is one recorded fan-out callback. */
private class FakeDisplayFilter {
  var installs = 0
  var uninstalls = 0
  var fanOut: (() -> Unit)? = null
  var failInstall: RuntimeException? = null

  val install: (() -> Unit) -> Registration = { callback ->
    failInstall?.let { throw it }
    installs++
    fanOut = callback
    Registration {
      uninstalls++
      fanOut = null
    }
  }

  fun fire() = fanOut?.invoke()
}

class SharedActivationFilterTest : StringSpec({
  "R7: nothing is installed until the first register; N registers install one filter" {
    val display = FakeDisplayFilter()
    val filter = SharedActivationFilter(display.install, log = {})
    display.installs shouldBe 0

    filter.register {}
    filter.register {}
    filter.register {}

    display.installs shouldBe 1
  }

  "one display event signals every registered window" {
    val display = FakeDisplayFilter()
    val filter = SharedActivationFilter(display.install, log = {})
    val seen = mutableListOf<String>()
    filter.register { seen += "a" }
    filter.register { seen += "b" }

    display.fire()

    seen shouldContainExactly listOf("a", "b")
  }

  "an unregistered window is no longer signalled; the last unregister removes the filter, a new register reinstalls it" {
    val display = FakeDisplayFilter()
    val filter = SharedActivationFilter(display.install, log = {})
    val seen = mutableListOf<String>()
    val a = filter.register { seen += "a" }
    val b = filter.register { seen += "b" }

    a.unregister()
    display.fire()
    seen shouldContainExactly listOf("b")
    display.uninstalls shouldBe 0

    b.unregister()
    b.unregister()
    display.uninstalls shouldBe 1

    filter.register {}
    display.installs shouldBe 2
  }

  "a throwing window does not keep the others from being signalled; logged by class only" {
    val display = FakeDisplayFilter()
    val logs = mutableListOf<String>()
    val filter = SharedActivationFilter(display.install, log = { logs += it })
    val seen = mutableListOf<String>()
    filter.register { throw IllegalStateException("/home/alice/secret") }
    filter.register { seen += "b" }

    display.fire()

    seen shouldContainExactly listOf("b")
    logs shouldContainExactly listOf("Current-branch activation signal failed: java.lang.IllegalStateException")
  }

  "a failing install propagates (the refresher's start rolls back) and a later register retries" {
    val display = FakeDisplayFilter()
    val filter = SharedActivationFilter(display.install, log = {})
    display.failInstall = IllegalStateException("display disposed")

    shouldThrow<IllegalStateException> { filter.register {} }

    display.failInstall = null
    filter.register {}
    display.installs shouldBe 1
  }

  "dispose removes the filter once; later registers throw and late events signal nobody" {
    val display = FakeDisplayFilter()
    val filter = SharedActivationFilter(display.install, log = {})
    val seen = mutableListOf<String>()
    val fanOut = run {
      filter.register { seen += "a" }
      checkNotNull(display.fanOut)
    }

    filter.dispose()
    filter.dispose()
    fanOut()

    display.uninstalls shouldBe 1
    seen shouldContainExactly emptyList()
    shouldThrow<IllegalStateException> { filter.register {} }
  }
})
