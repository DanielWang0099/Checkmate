package com.checkmate.app.config

import com.checkmate.app.BuildConfig // if unresolved, just use BuildConfig

object ApiConfig {
    // Emulator can reach your PC at 10.0.2.2
    val BASE_URL: String =
        if (BuildConfig.DEBUG) "http://10.0.2.2:8000" else "https://your-prod-domain/"
}