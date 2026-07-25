package com.gitlab.eclipse.navigation

/** Advanced-search scope tables, ported from search_input.ts:14-44. U-4: single source for builder + handler. */
object SearchScopes {
  const val GITLAB_COM_URL: String = "https://gitlab.com" // src/common/constants.ts:1

  data class ScopeChoice(val label: String, val scope: String)

  /** ALL_PROJECT_SEARCH_SCOPES :35-44 — project level (or undefined level). */
  val ALL_PROJECT: List<ScopeChoice> = listOf(
    ScopeChoice("Code", "blobs"),
    ScopeChoice("Issues", "issues"),
    ScopeChoice("Merge Requests", "merge_requests"),
    ScopeChoice("Wiki", "wiki_blobs"),
    ScopeChoice("Commits", "commits"),
    ScopeChoice("Comments", "notes"),
    ScopeChoice("Milestones", "milestones"),
    ScopeChoice("Users", "users"),
  )

  /** SELF_MANAGED_INSTANCE_SEARCH_SCOPES :14-24 — ALL_PROJECT + Projects. */
  val SELF_MANAGED_INSTANCE: List<ScopeChoice> = ALL_PROJECT + ScopeChoice("Projects", "projects")

  /** GITLAB_COM_SEARCH_SCOPES :26-33 — basic-search subset on .com. */
  val GITLAB_COM: List<ScopeChoice> = listOf(
    ScopeChoice("Issues", "issues"),
    ScopeChoice("Merge Requests", "merge_requests"),
    ScopeChoice("Comments", "notes"),
    ScopeChoice("Milestones", "milestones"),
    ScopeChoice("Users", "users"),
    ScopeChoice("Projects", "projects"),
  )
}
