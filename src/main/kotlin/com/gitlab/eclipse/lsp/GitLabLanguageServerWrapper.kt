package com.gitlab.eclipse.lsp

class GitLabLanguageServerWrapper {
  var languageServer: GitLabLanguageServer? = null
    set(value) {
      if (value !is GitLabLanguageServer) return
      field = value
    }
}
