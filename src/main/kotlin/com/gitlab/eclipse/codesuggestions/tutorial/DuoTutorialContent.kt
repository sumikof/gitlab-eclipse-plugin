package com.gitlab.eclipse.codesuggestions.tutorial

/**
 * The project and file the "GitLab Duo Tutorial" command creates, and the JavaScript walkthrough it
 * opens (design §8.2, §12.2).
 *
 * [TEXT] is a Kotlin constant, not a resource file: `src/main/resources` holds only `plugin.xml`,
 * `log4j2.xml` and `icons` today, and adding a resource for this one string would be the only
 * reason to touch that build shape (design §8.2, matching `McpConfigService.DEFAULT_CONFIG_TEMPLATE`).
 *
 * Adapted from the reference extension's `duo_tutorial.ts` (design §12.2): the VS Code-only Quick
 * Chat section is removed together with its `fibonacci` sample, every key binding is this plugin's
 * real Eclipse binding (`plugin.xml`) rather than VS Code's, the three chat commands use the real
 * editor context-menu labels (`plugin.xml`: Explain Code / Generate Tests / Refactor Code), and the
 * upstream Command/Control-per-OS mixup in the Code Generation section is fixed.
 */
object DuoTutorialContent {
  const val PROJECT_NAME: String = "GitLab Duo Tutorial"
  const val FILE_NAME: String = "duo_tutorial.js"

  val TEXT: String = """
    /*
      GitLab Duo Tutorial
      ===================
      This tutorial will walk you through several GitLab Duo features.

      It assumes the default keybindings.

      Adapted from the GitLab Workflow extension for VS Code (duo_tutorial.ts).
      MIT License, Copyright (c) 2020-present GitLab Inc.
      */

    /*
      1) Code Completion
      ------------------
      1. In the following snippet, put your cursor after 'const multiply'.
         Press Space.
      2. Code Completion is shown.
      3. To accept a single word from the suggestion, press Alt + right arrow
         key (macOS: Option + right arrow key).
      4. To accept a full line from the suggestion, press:
         - Windows, Linux: Control + right arrow key.
         - MacOS: Command + right arrow key.
      5. To accept the full suggestion, press Tab.
      6. To reject the suggestion, press Esc.
      7. If more than one suggestion is available, cycle through them with
         Alt + ] (next suggestion) and Alt + [ (previous suggestion).
      8. To request a suggestion manually, press:
         - Windows, Linux: Control + period (.)
         - MacOS: Command + period (.)
      */

    const multiply =

    /*
      2) Code Generation
      ------------------
      1. In the following snippet, put your cursor at the end of the line with the
         "// Write a simple express router" instruction.
      2. Press Enter. Pressing Enter after a comment instructs GitLab Duo
         to generate a longer code block.
      3. Wait for the code generation to finish. While Duo processes a response, a
         loading icon is shown in the gutter.
      4. Accept partial or full suggestions, as you did with Code Completion:
         1. To accept a single word from the suggestion, press Alt + right
            arrow key (macOS: Option + right arrow key).
         2. To accept a full line from the suggestion, press:
            - Windows, Linux: Control + right arrow key.
            - MacOS: Command + right arrow key.
         3. To accept the full suggestion, press Tab.
      5. Code generation also triggers in empty blocks. Try it by placing your
         cursor in the empty "computeRectangleArea" function block, then pressing
         Space.
      */

    // Write a simple express router with three routes: 'home', 'about', and 'contact'

    const computeRectangleArea = () => {};

    /*
      3) GitLab Duo Non-Agentic Chat
      -----------
      1. Select the following function to include it as context for your question.
      2. To open Chat, press:
         - Windows, Linux: Alt + D
         - MacOS: Option + D
      3. Ask GitLab Duo "How can I improve this function?"
      4. Like the suggestion from GitLab Duo? Click "Insert" on the top right
         corner of the chat code snippet to apply it.
      */

    function calculateFactorial(n) {
      let result = 1;
      for (let i = 1; i <= n; i++) {
        result = result * i;
      }
      return result;
    }

    /*
      4) Explain Code
      -----------------------
      1. Select the following complex code to include it as context for your query.
      2. Right-click to open the context menu.
      3. Select GitLab Duo Chat > Explain Code.
      */

    function memoizedDebounce(func, wait) {
      const cache = new Map();
      let timeoutId;

      return function (...args) {
        const key = JSON.stringify(args);
        clearTimeout(timeoutId);

        if (cache.has(key)) {
          return cache.get(key);
        }

        timeoutId = setTimeout(() => {
          const result = func.apply(this, args);
          cache.set(key, result);
        }, wait);
      };
    }

    /*
      5) Generate Tests
      -----------------
      1. Select the following function to include it as context for your query.
      2. Right-click to open the context menu, then select
         GitLab Duo Chat > Generate Tests.
      3. Review the generated tests.
      */

    function validateEmail(email) {
      const regex = /^[^\s@]+@[^\s@]+\.[^\s@]+${'$'}/;
      return regex.test(email);
    }

    /*
      6) Refactor Code
      ----------------
      1. Select the following function to include it as context for your query.
      2. Right-click to open the context menu, then select
         GitLab Duo Chat > Refactor Code.
      3. Like the suggestion from GitLab Duo? Click "Insert" on the top right
         corner of the chat code snippet to apply it.
      */

    function processUserData(data) {
      if (data.name) {
        if (data.age) {
          if (data.email) {
            return {
              name: data.name.trim(),
              age: parseInt(data.age),
              email: data.email.toLowerCase(),
            };
          } else {
            return null;
          }
        } else {
          return null;
        }
      } else {
        return null;
      }
    }
  """.trimIndent()
}
