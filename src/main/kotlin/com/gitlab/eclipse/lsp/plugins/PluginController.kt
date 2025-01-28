package com.gitlab.eclipse.lsp.plugins

/**
 * Classes that implement this interface and that are registered in a Koin module will automatically be registered
 * to handle messages from the Language Server.
 * Controllers should be public and should only have methods which
 * are marked with @PluginRequest or @PluginNotification.
 *
 *
 * Example:
 * ```
 * fun myModule() = module {
 *  single<GitLabDuoChatWebViewController> { GitLabDuoChatWebViewController() } bind PluginController::class
 * }
 *
 * @PluginRoute("duo-chat")
 * class DuoChatPluginController : PluginController {
 *   @PluginRequest("getCurrentFileContext") fun getCurrentFileContext() {}
 *   @PluginNotification("insertCodeSnippet") fun insertCodeSnippet(payload: Payload) {}
 * }
 * ```
 */
interface PluginController
