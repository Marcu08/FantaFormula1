package com.fantaformula1.app

import android.app.Application
import com.fantaformula1.app.core.AppContainer

/** Application: possiede il [AppContainer] condiviso per tutta la sessione. */
class FantaApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
