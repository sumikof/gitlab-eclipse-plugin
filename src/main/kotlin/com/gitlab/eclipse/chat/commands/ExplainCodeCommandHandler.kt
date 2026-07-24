package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.utils.PlatformUtils

class ExplainCodeCommandHandler(
  platformUtils: PlatformUtils = PlatformUtils(),
) : ChatCommandHandler(
  promptType = "explainCode",
  platformUtils = platformUtils
)
