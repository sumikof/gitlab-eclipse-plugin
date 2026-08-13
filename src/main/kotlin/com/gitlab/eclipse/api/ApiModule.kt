package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.http.GitLabHttpClient
import com.gitlab.eclipse.api.http.GitLabHttpClientFactory
import com.gitlab.eclipse.api.http.TlsMaterialLoader
import org.koin.dsl.module

val apiModule = module {
  single<TlsMaterialLoader> { TlsMaterialLoader() }
  single<GitLabHttpClientFactory> { GitLabHttpClientFactory(get(), get(), get()) }
  single<GitLabHttpClient> { GitLabHttpClient(get()) }
  single<GitLabApiClient> { GitLabApiClient(get(), get(), get()) }
  single<IssueService> { IssueService(get()) }
  single<CurrentUserService> { CurrentUserService(get()) }
  single<MergeRequestService> { MergeRequestService(get()) }
  single<ProjectDetailService> { ProjectDetailService(get()) }
  single<SnippetService> { SnippetService(get()) }
  single<SnippetQueryService> { SnippetQueryService(get(), get()) }
  single<GitLabGraphQlClient> { GitLabGraphQlClient(get()) }
  single<DiscussionService> { DiscussionService(get()) }
  single<DiscussionWriteService> { DiscussionWriteService(get(), get()) }
}
