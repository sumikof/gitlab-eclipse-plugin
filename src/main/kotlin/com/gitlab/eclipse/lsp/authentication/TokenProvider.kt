package com.gitlab.eclipse.lsp.authentication

interface TokenProvider {
  fun getToken(): String
}
