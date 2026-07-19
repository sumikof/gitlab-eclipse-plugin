package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.http.GitLabHttpClient
import com.gitlab.eclipse.api.http.GitLabHttpClientFactory
import org.koin.dsl.module

val apiModule = module {
  single<GitLabHttpClientFactory> { GitLabHttpClientFactory(get(), get()) }
  single<GitLabHttpClient> { GitLabHttpClient(get()) }
  single<GitLabApiClient> { GitLabApiClient(get(), get(), get()) }
  single<IssueService> { IssueService(get()) }
}
