package com.gitlab.eclipse.ci.actions

import com.gitlab.eclipse.currentbranch.CurrentBranchStateService
import com.gitlab.eclipse.currentbranch.WindowCurrentBranchState
import com.gitlab.eclipse.views.sidebar.GitLabSidebarView
import com.gitlab.eclipse.views.sidebar.autorefresh.RefreshReason
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.StringSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.ui.IWorkbenchWindow

/**
 * Status bar R15: after Create pipeline, a CI write or an MR auto-push, the window's sidebar refreshes when it is
 * open (as before); otherwise the window's current-branch state does, so the status trim updates with the sidebar
 * closed.
 */
class RefreshAfterWriteTest : StringSpec({
  val window = mockk<IWorkbenchWindow>()

  "an open sidebar is refreshed, and only the sidebar" {
    val sidebar = mockk<GitLabSidebarView>(relaxed = true)
    val service = mockk<CurrentBranchStateService>(relaxed = true)

    refreshAfterWrite(window, sidebar, service)

    verify(exactly = 1) { sidebar.refresh() }
    verify(exactly = 0) { service.ensureWindow(any()) }
  }

  "without a sidebar the window's current-branch state gets a coalesced MANUAL refresh" {
    val state = mockk<WindowCurrentBranchState>(relaxed = true)
    val service = mockk<CurrentBranchStateService>()
    every { service.ensureWindow(window) } returns state

    refreshAfterWrite(window, null, service)

    verify(exactly = 1) { state.requestRefresh(RefreshReason.MANUAL) }
  }

  "neither a sidebar nor a service (or no window, or no state) is a no-op that never throws" {
    val service = mockk<CurrentBranchStateService>()
    every { service.ensureWindow(any()) } returns null
    val throwing = mockk<CurrentBranchStateService>()
    every { throwing.ensureWindow(any()) } throws IllegalStateException("closing")

    shouldNotThrowAny {
      refreshAfterWrite(window, null, null)
      refreshAfterWrite(null, null, service)
      refreshAfterWrite(window, null, service)
      refreshAfterWrite(window, null, throwing)
    }
  }
})
