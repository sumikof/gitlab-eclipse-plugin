package com.gitlab.eclipse.clone

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.core.runtime.IProgressMonitor

class EclipseProgressMonitorAdapterTest : StringSpec({
  "isCancelled reports the Eclipse monitor's cancellation" {
    val delegate = mockk<IProgressMonitor>(relaxed = true)
    every { delegate.isCanceled } returnsMany listOf(false, true)
    val adapter = EclipseProgressMonitorAdapter(delegate)
    adapter.isCancelled shouldBe false
    adapter.isCancelled shouldBe true
  }

  "start opens the Eclipse task exactly once and beginTask becomes a subTask" {
    val delegate = mockk<IProgressMonitor>(relaxed = true)
    val adapter = EclipseProgressMonitorAdapter(delegate)
    adapter.start(2)
    adapter.beginTask("receiving objects", 100)
    adapter.beginTask("resolving deltas", 50)
    verify(exactly = 1) { delegate.beginTask(any(), any()) }
    verify { delegate.subTask("receiving objects") }
    verify { delegate.subTask("resolving deltas") }
  }

  "update forwards completed units as worked" {
    val delegate = mockk<IProgressMonitor>(relaxed = true)
    EclipseProgressMonitorAdapter(delegate).update(7)
    verify { delegate.worked(7) }
  }

  "showDuration and endTask do not touch the Eclipse monitor" {
    val delegate = mockk<IProgressMonitor>(relaxed = true)
    val adapter = EclipseProgressMonitorAdapter(delegate)
    adapter.showDuration(true)
    adapter.endTask()
    verify(exactly = 0) { delegate.done() }
  }
})
