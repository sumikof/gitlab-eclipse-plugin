package com.gitlab.eclipse.authentication

import org.eclipse.ui.PlatformUI
import org.eclipse.ui.services.ISourceProviderService
import org.koin.dsl.module

val authModule = module {
  single<AuthenticationStateService> { AuthenticationStateService() }

  // Resolve the workbench-created source provider instance (declared in plugin.xml) so the Koin
  // singleton IS the provider that fires `gitlab_sign_in_required` source changes. A second instance
  // would fire into the void (same accident as ChatModule's P1-K).
  single<AuthenticationSourceProvider> {
    PlatformUI
      .getWorkbench()
      .getService(ISourceProviderService::class.java)
      .getSourceProvider(AuthenticationSourceProvider.SIGN_IN_REQUIRED_KEY) as AuthenticationSourceProvider
  }
}
