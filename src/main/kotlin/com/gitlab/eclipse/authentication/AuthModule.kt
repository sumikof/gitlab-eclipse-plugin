package com.gitlab.eclipse.authentication

import org.koin.dsl.module

val authModule = module {
  single<AuthenticationStateService> { AuthenticationStateService() }
}
