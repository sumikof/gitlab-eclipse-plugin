package com.gitlab.eclipse.quickchat

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import java.util.function.Consumer
import kotlin.jvm.functions.FunctionN

class ChatAPI {
    companion object {
        val HARD_CODED_RESPONSE: String = """
            To fork a project in GitLab, follow these steps:

            Go to the main page of the project you want to fork.
            Look for the "Fork" button in the upper right corner of the page. It usually has a fork icon (looks like a Y-shaped road) next to it.
            Click on the "Fork" button.
            On the next page, you'll see options to configure your fork:
            Choose where to fork the project (your personal namespace or a group you have access to).
            Optionally, you can change the project's name and slug (the part of the URL that identifies the project).
            You can also modify the project's description if you wish.
            Select which branches you want to include in your fork:
            All branches
            Protected branches only
            Default branch only
            After configuring your options, click the "Fork project" button at the bottom of the page.
            GitLab will create a copy of the project in your chosen namespace. This may take a moment, depending on the size of the project.
            Once the fork is created, you'll have your own copy of the project that you can work on independently. You can make changes, create new branches, and even submit merge requests back to the original project if you want to contribute your changes.

            Remember:

            A fork is an independent copy of the repository.
            Changes you make to your fork don't affect the original project.
            You can sync your fork with the original project to keep it up-to-date.
            Forking is useful for experimenting with changes or starting your own version of a project.
            Is there anything specific about the forking process you'd like more information on? Your feedback helps me provide better assistance!
        """.trimIndent()
    }

     fun chat(callback: Consumer<String>) {
        HARD_CODED_RESPONSE.chunked(50) {
            callback.accept(it.toString())
            Thread.sleep(250)
        }
    }
}