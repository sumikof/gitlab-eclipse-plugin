package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.utils.PlatformUtils

class GenerateTestsCommandHandler(
  platformUtils: PlatformUtils = PlatformUtils(),
) : ChatCommandHandler(
  promptType = "generateTests",
  platformUtils = platformUtils
)
