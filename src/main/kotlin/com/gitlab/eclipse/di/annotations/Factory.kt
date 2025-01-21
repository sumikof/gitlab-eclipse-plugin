package com.gitlab.eclipse.di.annotations

import com.gitlab.eclipse.di.utils.Loading

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Factory(val loading: Loading = Loading.LAZY)

interface SingletonFactory<T> {
  fun create(): T
}
