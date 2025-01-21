package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.di.annotations.Service
import org.eclipse.lsp4j.services.LanguageServer

@Service
class GitLabLanguageServerWrapper {
  companion object {
    private var languageServerProxy: GitLabLanguageServer? = null
  }

  init {
    println("GitLabLanguageServerWrapper instantiated.")
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
