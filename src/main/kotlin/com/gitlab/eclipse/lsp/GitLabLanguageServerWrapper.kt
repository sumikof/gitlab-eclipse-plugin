package com.gitlab.eclipse.lsp

import org.eclipse.lsp4j.services.LanguageServer

class GitLabLanguageServerWrapper {
  companion object {
    private var languageServerProxy: GitLabLanguageServer? = null
  }

  val languageServer: GitLabLanguageServer?
    get() = languageServerProxy

  fun registerLanguageServer(newLanguageServerProxy: LanguageServer) {
    if (newLanguageServerProxy !is GitLabLanguageServer) {
      return
    }

    languageServerProxy = newLanguageServerProxy
  }
}
