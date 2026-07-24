package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.utils.PlatformUtils

class RefactorCodeCommandHandler(
  platformUtils: PlatformUtils = service<PlatformUtils>(),
) : ChatCommandHandler(
  promptType = "refactorCode",
  platformUtils = platformUtils
)
