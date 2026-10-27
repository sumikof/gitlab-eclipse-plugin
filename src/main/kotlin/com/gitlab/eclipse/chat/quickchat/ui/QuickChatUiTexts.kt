package com.gitlab.eclipse.chat.quickchat.ui

import com.gitlab.eclipse.chat.quickchat.QuickChatTexts

/** The user-facing texts of the Quick Chat SWT layer (design §14, I4); the conversation's own are in [QuickChatTexts]. */
object QuickChatUiTexts {
  /** The copy-text dialog's title when a popup closes with an unsent message (design §9.5). */
  const val DIALOG_TITLE = QuickChatTexts.ITEM_TITLE

  const val UNSENT_DRAFT_MESSAGE =
    "The Quick Chat popup was closed with an unsent message. Copy it from here if you want to keep it."

  /** A code block's Copy landed on the clipboard (design §9.8). */
  const val CODE_COPIED = "Code copied to clipboard."

  /** Insert into an editor whose input refused editing (design §9.6 step 2). */
  const val EDITOR_NOT_EDITABLE = "This editor cannot be edited."

  /** Insert failed; names neither the code nor the file (design §9.6 step 6, NFR-4). */
  const val INSERT_FAILED = "Could not insert the code."

  /** Fix with GitLab Duo opened the marker in an editor that is not a text editor (QCHAT-05). */
  const val FIX_NOT_TEXT_EDITOR = "Fix with GitLab Duo works only in text editors."

  /** I4: the popup's title bar. */
  fun popupTitle(oneBasedLine: Int): String = "${QuickChatTexts.ITEM_TITLE} — line $oneBasedLine"
}
