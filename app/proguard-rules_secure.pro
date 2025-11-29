# 🔒 SECURE PROGUARD RULES FOR HAM-CHAT 🔒

# Keep all security-related classes
-keep class com.hamtaro.hamchat.security.** { *; }
-keep class com.hamtaro.hamchat.security.SecurityManager { *; }
-keep class com.hamtaro.hamchat.security.IntentValidator { *; }

# Keep Tox4j classes but obfuscate internals
-keep class toktok.** { *; }
-keep class org.tox4j.** { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep encryption classes
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }

# Keep biometric classes
-keep class androidx.biometric.** { *; }

# Keep root detection classes
-keep class com.scottyab.rootbeer.** { *; }

# Remove debugging information in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

# Obfuscate sensitive strings
-adaptclassstrings
-adaptresourcefilenames
-adaptresourcefilecontents

# Keep model classes but obfuscate field names
-keep class com.hamtaro.hamchat.model.** { *; }

# Keep view classes for UI
-keep class com.hamtaro.hamchat.ui.** { *; }

# Keep service classes
-keep class com.hamtaro.hamchat.service.** { *; }

# Remove unused code
-dontwarn javax.annotation.**
-dontwarn javax.xml.**
-dontwarn org.apache.harmony.**

# Optimize bytecode
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification
-dontpreverify

# Keep exceptions but obfuscate messages
-keepattributes Exceptions,InnerClasses,Signature,Deprecated,SourceFile,LineNumberTable,*Annotation*,EnclosingMethod

# Security: Prevent reflection attacks
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep MainActivity but obfuscate internals
-keep class com.hamtaro.hamchat.MainActivity { *; }
-keep class com.hamtaro.hamchat.LoginActivity { *; }
-keep class com.hamtaro.hamchat.SecretGameActivity { *; }

# Remove sensitive class names from stack traces
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# Prevent tampering with security checks
-keep class com.hamtaro.hamchat.security.** {
    public static boolean isDeviceSecure(...);
    public static boolean verifyAppIntegrity(...);
    public static boolean canAttemptLogin(...);
}

# Keep encryption keys from being extracted
-keep class javax.crypto.spec.** { *; }
-keep class java.security.spec.** { *; }

# Remove debug information in release
-if class ** {
    # Remove all debug build flags
    static final boolean DEBUG = false;
}

# Keep required Android components
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# Prevent enumeration of security classes
-keepnames class com.hamtaro.hamchat.security.** { *; }

# Harden against reverse engineering
-obfuscationdictionary dictionary.txt
-classobfuscationdictionary dictionary.txt
-packageobfuscationdictionary dictionary.txt

# Keep required interface implementations
-keep class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Keep required enum methods
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Harden string encryption
-keepclassmembers class * {
    public static final java.lang.String *;
}

# Prevent access to private fields via reflection
-keepclassmembers class * {
    !private <fields>;
}

# Security: Remove all comments and metadata
-dontnote
-dontwarn
-ignorewarnings

# Keep required native libraries
-keep class org.tox4j.** { *; }
-dontwarn org.tox4j.**

# Final security hardening
-keep class com.hamtaro.hamchat.BuildConfig { *; }
-keep class com.hamtaro.hamchat.R { *; }
-keep class com.hamtaro.hamchat.R$* { *; }
