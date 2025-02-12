package com.gitlab.eclipse.lsp.messages

data class GitDiffParams(
  val repositoryUri: String,
  val branch: String? = null
)
