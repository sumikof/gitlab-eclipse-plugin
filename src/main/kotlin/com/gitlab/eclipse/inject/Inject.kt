package com.gitlab.eclipse.inject

import org.koin.core.context.GlobalContext.get

inline fun <reified T : Any> lazyService(): Lazy<T> = get().inject<T>()
inline fun <reified T : Any> service(): T = get().get<T>()
