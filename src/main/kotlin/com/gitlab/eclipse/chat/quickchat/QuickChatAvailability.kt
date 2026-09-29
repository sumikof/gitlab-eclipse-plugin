package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.chat.DuoChatStateService

/**
 * The [QuickChatSession] availability check over the language server's Duo Chat state (design §9.2
 * step 4, §14): null while enabled, else the first engaged check's details or the default text.
 */
fun duoChatAvailability(state: DuoChatStateService): () -> String? = {
  if (state.isEnabled) null else state.getFirstEngagedCheck()?.details ?: QuickChatTexts.UNAVAILABLE_DEFAULT
}
