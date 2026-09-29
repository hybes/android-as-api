plugins { id("com.android.application") }

android {
    namespace = "dev.phoneapi"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        applicationId = "dev.phoneapi"
        minSdk = 34
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "dev.phoneapi.ApiChecks"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies { implementation("org.nanohttpd:nanohttpd:2.3.1") }
