package com.gitlab.eclipse.ci

import com.gitlab.eclipse.currentbranch.StatusTrimPresenter
import com.gitlab.eclipse.currentbranch.trimIconName
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

private data class IconCase(val status: String?, val allowFailure: Boolean, val expected: CiStatusIcon)

private val CASES = listOf(
  IconCase("success", false, CiStatusIcon.SUCCESS),
  IconCase("failed", false, CiStatusIcon.FAILED),
  IconCase("failed", true, CiStatusIcon.FAILED_ALLOWED),
  IconCase("success", true, CiStatusIcon.SUCCESS),
  IconCase("running", false, CiStatusIcon.RUNNING),
  IconCase("created", false, CiStatusIcon.PENDING),
  IconCase("waiting_for_resource", false, CiStatusIcon.PENDING),
  IconCase("preparing", false, CiStatusIcon.PENDING),
  IconCase("pending", false, CiStatusIcon.PENDING),
  IconCase("scheduled", false, CiStatusIcon.PENDING),
  IconCase("skipped", false, CiStatusIcon.SKIPPED),
  IconCase("canceled", false, CiStatusIcon.CANCELED),
  IconCase("canceling", false, CiStatusIcon.CANCELED),
  IconCase("manual", false, CiStatusIcon.MANUAL),
  IconCase(null, false, CiStatusIcon.UNKNOWN),
  IconCase("", false, CiStatusIcon.UNKNOWN),
  IconCase("new_status", false, CiStatusIcon.UNKNOWN),
  // CiStatus looks statuses up case-sensitively, so the icon mapping does too.
  IconCase("RUNNING", false, CiStatusIcon.UNKNOWN),
)

private val ICON_NAMES = mapOf(
  CiStatusIcon.SUCCESS to "pipeline_success",
  CiStatusIcon.FAILED to "pipeline_failed",
  CiStatusIcon.FAILED_ALLOWED to "pipeline_warning",
  CiStatusIcon.RUNNING to "pipeline_running",
  CiStatusIcon.PENDING to "pipeline_pending",
  CiStatusIcon.CANCELED to "pipeline_canceled",
  CiStatusIcon.SKIPPED to "pipeline_skipped",
  CiStatusIcon.MANUAL to "pipeline_manual",
  CiStatusIcon.UNKNOWN to "pipeline_unknown",
)

class CiStatusIconTest : DescribeSpec({
  describe("ciStatusIcon") {
    withData(nameFn = { "${it.status} allowFailure=${it.allowFailure}" }, CASES) { c ->
      ciStatusIcon(c.status, c.allowFailure) shouldBe c.expected
    }
  }

  describe("iconName") {
    withData(nameFn = { it.key.name }, ICON_NAMES.entries.toList()) { (icon, name) ->
      icon.iconName shouldBe name
    }
    it("covers every icon") { ICON_NAMES.keys shouldBe CiStatusIcon.entries.toSet() }
  }

  describe("agrees with the status-bar trim for allowFailure=false") {
    withData(nameFn = { "status='${it.status}'" }, CASES.filter { !it.allowFailure }) { c ->
      ciStatusIcon(c.status).iconName shouldBe trimIconName(StatusTrimPresenter.iconFor(c.status))
    }
  }
})
