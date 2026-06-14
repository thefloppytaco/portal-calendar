plugins {
  id("com.android.application")
}

android {
  namespace = "com.portal.calendar"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.portal.calendar"
    minSdk = 28
    // Portal+ gen-1 is API 28; staying at 28 avoids newer background restrictions.
    targetSdk = 28
    versionCode = 20
    versionName = "3.1"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

dependencies {
  // Phone-facing config web server (same pattern as portal-remote).
  implementation("org.nanohttpd:nanohttpd:2.3.1")
  // QR code for the setup URL.
  implementation("com.google.zxing:core:3.5.3")
  // iCalendar parsing incl. RRULE/EXDATE recurrence expansion. Pure Java, Android-safe.
  implementation("net.sf.biweekly:biweekly:0.6.8")
  // CalDAV (PROPFIND/PUT) — HttpURLConnection can't send PROPFIND.
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
