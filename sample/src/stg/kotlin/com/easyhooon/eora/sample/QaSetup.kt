package com.easyhooon.eora.sample

import android.app.Activity
import android.app.Application
import com.easyhooon.eora.Eora
import com.easyhooon.eora.EoraConfig

object QaSetup {
    fun install(application: Application) {
        Eora.install(
            application,
            EoraConfig(
                uploader = DemoUploader(application),
                screenIdentifierProvider = { activity: Activity -> activity.javaClass.simpleName },
                metadataProvider = { mapOf("api_env" to "stg-2", "user_email" to "never@reported.example") },
                // user_email is not allowlisted, so it never reaches the report.
                allowedCustomKeys = setOf("api_env"),
            ),
        )
    }
}
