package com.gitlab.eclipse.authentication

interface TokenProvider {
  fun getToken(): String
}
