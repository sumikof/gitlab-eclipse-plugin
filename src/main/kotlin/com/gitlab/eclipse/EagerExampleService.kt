package com.gitlab.eclipse

import com.gitlab.eclipse.di.annotations.Service
import com.gitlab.eclipse.di.utils.Loading

@Service(Loading.EAGER)
class EagerExampleService {
  init {
    println("EagerExampleService instantiated")
  }
}
