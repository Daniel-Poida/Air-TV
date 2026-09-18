plugins { alias(libs.plugins.android.application) }
android {
    namespace = "dev.airtv.airdrop.lab"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.airtv.airdrop.lab"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "0.0.2-lab"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    providers.gradleProperty("prototypeKeystore").orNull?.let {
        signingConfigs.getByName("debug").storeFile = file(it)
    }
}
dependencies { implementation(project(":airdrop-core")) }
