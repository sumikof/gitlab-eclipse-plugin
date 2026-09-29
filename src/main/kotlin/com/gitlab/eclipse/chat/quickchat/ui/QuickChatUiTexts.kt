package com.gitlab.eclipse.chat.quickchat.ui

import com.gitlab.eclipse.chat.quickchat.QuickChatTexts

/** The user-facing texts of the Quick Chat SWT layer (design §14, I4); the conversation's own are in [QuickChatTexts]. */
object QuickChatUiTexts {
  /** The copy-text dialog's title when a popup closes with an unsent message (design §9.5). */
  const val DIALOG_TITLE = QuickChatTexts.ITEM_TITLE

  const val UNSENT_DRAFT_MESSAGE =
    "The Quick Chat popup was closed with an unsent message. Copy it from here if you want to keep it."

  /** I4: the popup's title bar. */
  fun popupTitle(oneBasedLine: Int): String = "${QuickChatTexts.ITEM_TITLE} — line $oneBasedLine"
}
