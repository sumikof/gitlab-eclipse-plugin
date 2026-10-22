package com.gitlab.eclipse.currentbranch

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly

/**
 * T5b: the hop a trim action's success takes back to the UI thread. It is captured while the control is alive, so a
 * POST that completes after the trim was disposed (window close, trim rebuild) never touches the disposed widget —
 * otherwise `getDisplay()` throws, the write's catch-all reports "The action failed" for a write that succeeded.
 */
class TrimUiHopTest : StringSpec({
  "a live display runs the block through asyncExec" {
    val queued = mutableListOf<Runnable>()
    val ran = mutableListOf<String>()

    uiHopUnlessDisposed({ false }, { queued += it })({ ran += "refresh" })
    queued.forEach { it.run() }

    ran shouldContainExactly listOf("refresh")
  }

  "a disposed control (display) never throws and runs nothing" {
    val queued = mutableListOf<Runnable>()

    shouldNotThrowAny { uiHopUnlessDisposed({ true }, { queued += it })({ error("must not run") }) }

    queued.shouldBeEmpty()
  }

  "a display disposed between the check and the scheduling is swallowed" {
    shouldNotThrowAny {
      uiHopUnlessDisposed({ false }, { throw IllegalStateException("Device is disposed") })({})
    }
  }
})
