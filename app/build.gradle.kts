import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Public client configuration comes from -P properties, environment variables or local.properties.
// None of these are secrets; private keys never belong in the APK.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun config(name: String, default: String = ""): String =
    (project.findProperty(name) as String?)
        ?: System.getenv(name.uppercase().replace('.', '_'))
        ?: localProps.getProperty(name)
        ?: default
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.embeddablechatbot.android"
    compileSdk = 37

    defaultConfig {
        applicationId = config("chatbot.applicationId", "com.embeddablechatbot.android")
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SUPABASE_URL", quoted(config("chatbot.supabaseUrl")))
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", quoted(config("chatbot.supabasePublishableKey")))
        buildConfigField("String", "DEFAULT_CHATBOT_ID", quoted(config("chatbot.chatbotId")))
        buildConfigField("String", "OAUTH_RETURN_URI", quoted("chatbotmail://oauth/gmail"))
        // Firebase Cloud Messaging; push stays off while these are empty.
        buildConfigField("String", "FIREBASE_APP_ID", quoted(config("firebase.appId")))
        buildConfigField("String", "FIREBASE_API_KEY", quoted(config("firebase.apiKey")))
        buildConfigField("String", "FIREBASE_PROJECT_ID", quoted(config("firebase.projectId")))
        buildConfigField("String", "FIREBASE_SENDER_ID", quoted(config("firebase.senderId")))
        buildConfigField("boolean", "MIGRATE_LOCAL_PLATFORM", config("chatbot.migrateLocalPlatform", "false").toBoolean().toString())
    }

    signingConfigs {
        // Release signing is supplied outside source control (keystore.properties, gitignored).
        val keystoreFile = rootProject.file("keystore.properties")
        if (keystoreFile.exists()) {
            val ks = Properties().apply { keystoreFile.inputStream().use { load(it) } }
            create("release") {
                storeFile = rootProject.file(ks.getProperty("storeFile"))
                storePassword = ks.getProperty("storePassword")
                keyAlias = ks.getProperty("keyAlias")
                keyPassword = ks.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "DEFAULT_PLATFORM_URL", quoted(config("chatbot.platformUrl.debug", "http://10.0.2.2:3000")))
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "DEFAULT_PLATFORM_URL", quoted(config("chatbot.platformUrl.release")))
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "false")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        abortOnError = true
        checkDependencies = true
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:mail"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    implementation(libs.firebase.messaging)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
