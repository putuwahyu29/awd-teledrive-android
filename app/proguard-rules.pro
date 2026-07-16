# Proguard rules for Awd TeleDrive

# TDLib (Telegram Database Library)
-keep class org.drinkless.tdlib.** { *; }
-keep class org.drinkless.td.** { *; }

# JNA (Java Native Access)
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**

# Lazysodium
-keep class com.goterl.lazysodium.** { *; }

# Argon2Kt
-keep class com.lambdapioneer.argon2kt.** { *; }

# Room & SQLCipher
-keep class net.zetetic.database.sqlcipher.** { *; }
-keep class net.zetetic.database.** { *; }

# Hilt/Dagger
-keep class dagger.hilt.android.internal.** { *; }

# General JNI keep rules
-keepclasseswithmembernames class * {
    native <methods>;
}

# Preserve Line Numbers for Crash Reporting (Optional)
-keepattributes SourceFile,LineNumberTable
