package com.gitlab.eclipse.lsp

class GitLabLanguageServerWrapper {
  companion object {
    private var languageServerProxy: GitLabLanguageServer? = null
  }

  val languageServer: GitLabLanguageServer?
    get() = languageServerProxy

  fun registerLanguageServer(newLanguageServerProxy: GitLabLanguageServer) {
    languageServerProxy = newLanguageServerProxy
  }

  fun unregisterLanguageServer() {
    languageServerProxy = null
  }
}
