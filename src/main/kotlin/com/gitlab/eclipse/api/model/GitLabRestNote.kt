package com.gitlab.eclipse.api.model

/** REST shape of a single MR note; only [body] is used, for the edit pre-check (design §13). */
data class GitLabRestNote(val body: String?)
