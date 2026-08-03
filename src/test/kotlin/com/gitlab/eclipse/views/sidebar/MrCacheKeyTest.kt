package com.gitlab.eclipse.views.sidebar

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotStartWith

/**
 * Pure cache-key derivation backing `mrVersionCache`/`mrConnectionTagsCache` in
 * `GitLabSidebarView.loadMrChildren` (Codex round-3 P2-1): the merge request's web URL when
 * usable, otherwise a per-MR numeric fallback — because Gson can leave the declared-non-null
 * `webUrl` null or blank at runtime, and keying every such MR by that one degenerate value would
 * render one MR's cached changed files under another.
 */
class MrCacheKeyTest : StringSpec({
  val mrUrl = "https://gitlab.example.com/group/project/-/merge_requests/42"

  "a usable web URL is returned unchanged as the key" {
    mrCacheKey(mrUrl, 42L, 7L) shouldBe mrUrl
  }

  "a null web URL falls back to the numeric form" {
    mrCacheKey(null, 42L, 7L) shouldBe "mr-id:42/7"
  }

  "a blank web URL falls back to the numeric form" {
    mrCacheKey("   ", 42L, 7L) shouldBe "mr-id:42/7"
  }

  "two different merge requests with absent web URLs get DIFFERENT keys (the round-2 regression)" {
    mrCacheKey(null, 42L, 7L) shouldNotBe mrCacheKey(null, 42L, 8L)
    mrCacheKey(null, 42L, 7L) shouldNotBe mrCacheKey(null, 43L, 7L)
    mrCacheKey(null, 42L, 7L) shouldNotBe mrCacheKey("", 42L, 8L)
  }

  "the fallback and the numeric parts of two MRs never concatenate into the same key" {
    // projectId=1, iid=27 vs projectId=12, iid=7: the '/' separator keeps them apart.
    mrCacheKey(null, 1L, 27L) shouldNotBe mrCacheKey(null, 12L, 7L)
  }

  "the fallback form can never equal a key derived from a real web URL" {
    val fallback = mrCacheKey(null, 42L, 7L)
    fallback shouldNotStartWith "http://"
    fallback shouldNotStartWith "https://"
  }
})
