package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.GitLabGraphQlClient
import com.gitlab.eclipse.chat.chatModule
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.mockk
import kotlinx.coroutines.isActive
import org.eclipse.ui.preferences.ScopedPreferenceStore
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class QuickChatWiringTest : DescribeSpec({
  beforeSpec {
    startKoin {
      modules(
        chatModule,
        module {
          single<GitLabApiClient> { mockk() }
          single<GitLabGraphQlClient> { mockk() }
          single<ScopedPreferenceStore> { mockk() }
        },
      )
    }
  }

  afterSpec {
    GlobalContext.get().get<QuickChatRuntime>().close()
    stopKoin()
  }

  it("resolves the service and one shared runtime from the chat module") {
    val koin = GlobalContext.get()
    koin.get<QuickChatService>()
    val runtime = koin.get<QuickChatRuntime>()
    runtime shouldBeSameInstanceAs koin.get<QuickChatRuntime>()
    runtime.scope.isActive shouldBe true
  }
})
