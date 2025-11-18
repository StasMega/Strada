package com.stasmega.strada

import android.app.Application
import com.google.android.material.color.DynamicColors

class StradaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}