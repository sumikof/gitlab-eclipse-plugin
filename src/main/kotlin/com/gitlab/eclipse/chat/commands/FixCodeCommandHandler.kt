package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.utils.PlatformUtils

class FixCodeCommandHandler(
  platformUtils: PlatformUtils = PlatformUtils(),
) : ChatCommandHandler(
  promptType = "fixCode",
  platformUtils = platformUtils
)
