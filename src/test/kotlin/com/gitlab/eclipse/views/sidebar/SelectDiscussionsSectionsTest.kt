package com.gitlab.eclipse.views.sidebar

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

private const val TARGET_URL = "https://gitlab.example.com"
private const val TARGET_FP = "fp-target"
private const val TARGET_PROJECT_ID = 7L
private const val TARGET_MR_IID = 42L

private fun sectionOf(
  instanceUrl: String = TARGET_URL,
  authFingerprint: String = TARGET_FP,
  projectId: Long = TARGET_PROJECT_ID,
  mrIid: Long = TARGET_MR_IID,
) = DiscussionsSectionNode(
  sourceInstanceUrl = instanceUrl,
  sourceAuthFingerprint = authFingerprint,
  projectId = projectId,
  mrIid = mrIid,
  mrGid = "gid://gitlab/MergeRequest/99",
  mrSha = "abc123",
  namespaceWithPath = "group/project",
)

private fun select(sections: List<DiscussionsSectionNode>) =
  selectDiscussionsSections(sections, TARGET_URL, TARGET_FP, TARGET_PROJECT_ID, TARGET_MR_IID)

/**
 * The matching rule behind `GitLabSidebarView.resolveDiscussionsSections`, which the SWT-bound view
 * delegates to so it is reachable headless. The load-bearing case is the first one: one merge
 * request can own two section nodes (it appears under both "Merge requests assigned to me" and
 * "For current branch"), and the post-write re-fetch must see BOTH — refreshing only the first
 * would let the `[Send again]` prompt claim a thread was reloaded when the one on screen was not.
 */
class SelectDiscussionsSectionsTest : DescribeSpec({
  describe("selectDiscussionsSections") {
    it("returns both sections when the same merge request is displayed twice") {
      val assigned = sectionOf()
      val currentBranch = sectionOf()

      select(listOf(assigned, currentBranch)) shouldContainExactly listOf(assigned, currentBranch)
    }

    it("preserves the given (tree) order") {
      val first = sectionOf()
      val second = sectionOf()

      select(listOf(second, first)) shouldContainExactly listOf(second, first)
    }

    it("keeps only the matching sections out of a mixed tree") {
      val match = sectionOf()
      val otherMr = sectionOf(mrIid = TARGET_MR_IID + 1)
      val otherProject = sectionOf(projectId = TARGET_PROJECT_ID + 1)

      select(listOf(otherMr, match, otherProject)) shouldContainExactly listOf(match)
    }

    it("matches through instance-url normalization on both sides") {
      val trailingSlashOnNode = sectionOf(instanceUrl = "$TARGET_URL/")
      val plainNode = sectionOf()

      // node has the trailing slash, target does not
      select(listOf(trailingSlashOnNode)) shouldContainExactly listOf(trailingSlashOnNode)
      // target has the trailing slash, node does not
      selectDiscussionsSections(
        listOf(plainNode),
        "$TARGET_URL/",
        TARGET_FP,
        TARGET_PROJECT_ID,
        TARGET_MR_IID,
      ) shouldContainExactly listOf(plainNode)
    }

    it("excludes a section fetched over a different account on the same instance") {
      select(listOf(sectionOf(authFingerprint = "fp-other"))) shouldBe emptyList()
    }

    it("excludes a section fetched from a different instance") {
      select(listOf(sectionOf(instanceUrl = "https://gitlab.other.test"))) shouldBe emptyList()
    }

    it("returns an empty list when nothing matches") {
      select(emptyList()) shouldBe emptyList()
    }
  }
})
