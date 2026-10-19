package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.model.GitLabMergeRequest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * CI-04 (design §2.4): when a soft refresh rebuilds the current-branch MR side, the replaced MR
 * nodes' diff-version cache entries and their discussion-section registry keys must go; when the
 * side was reused (same node instances), nothing may be invalidated.
 */
class StaleMrKeysTest : StringSpec({
  fun discussions() =
    DiscussionsSectionNode(
      sourceInstanceUrl = "https://gitlab.example.com",
      sourceAuthFingerprint = "fp",
      projectId = 1,
      mrIid = 7,
      mrGid = "gid://gitlab/MergeRequest/9",
      mrSha = "s1",
      namespaceWithPath = "g/p",
    )

  fun mrNode(sha: String, vararg loaded: SidebarNode) =
    MergeRequestNode(
      GitLabMergeRequest(7, 7, "m7", 1, "https://gitlab.example.com/g/p/-/merge_requests/7", "opened", sha = sha),
    ).apply { if (loaded.isNotEmpty()) loadedChildren = loaded.toList() }

  "a rebuilt MR node yields its cache key and its discussion-section ids" {
    val section = discussions()
    val old = listOf<SidebarNode>(mrNode("s1", OverviewNode("https://x"), section), MessageNode("issue"))
    val new = listOf<SidebarNode>(mrNode("s2"))

    staleMrKeys(old, new) shouldBe StaleMrKeys(
      discussionNodeIds = setOf(section.nodeId),
      mrCacheEntries = setOf("https://gitlab.example.com/g/p/-/merge_requests/7"),
    )
  }

  "a reused MR node (same instance) yields nothing" {
    val mr = mrNode("s1", discussions())
    staleMrKeys(listOf(mr), listOf(MessageNode("pipeline"), mr)) shouldBe StaleMrKeys(emptySet(), emptySet())
  }

  "no MR before yields nothing" {
    staleMrKeys(listOf(MessageNode("Loading…")), listOf(mrNode("s1"))) shouldBe StaleMrKeys(emptySet(), emptySet())
  }
})
