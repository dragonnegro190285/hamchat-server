# Build Instructions for Ham-Chat

## Prerequisites

1. **Java Development Kit (JDK)** 8 or higher
2. **Android SDK** with API level 28-34
3. **Gradle** 8.1.2 or higher

## Setup Steps

### 1. Download Gradle Wrapper
```bash
# Download gradle-wrapper.jar
curl -L -o gradle/wrapper/gradle-wrapper.jar https://github.com/gradle/gradle/raw/v8.1.2/gradle/wrapper/gradle-wrapper.jar
```

### 2. Set Environment Variables
```bash
# Set JAVA_HOME
export JAVA_HOME=/path/to/your/jdk

# Set ANDROID_HOME
export ANDROID_HOME=/path/to/your/android/sdk
```

### 3. Build the APK

#### Debug APK (for testing)
```bash
./gradlew assembleDebug
```

#### Release APK (optimized, 8-12MB)
```bash
./gradlew assembleRelease
```

## APK Location

After building, the APK will be located at:
- **Debug**: `app/build/outputs/apk/debug/app-debug.apk`
- **Release**: `app/build/outputs/apk/release/app-release.apk`

## Optimization Features

The build is configured to produce an APK between 8-12MB through:

1. **Code Shrinking**: R8 optimization removes unused code
2. **Resource Shrinking**: Removes unused resources
3. **ProGuard**: Obfuscates and optimizes code
4. **Zip Align**: Optimizes APK for faster installation
5. **Minimal Dependencies**: Only essential libraries included

## Manual Build Alternative

If Gradle wrapper doesn't work, you can build directly:

```bash
# Install gradle globally first, then:
gradle assembleRelease
```

## Troubleshooting

### Gradle Wrapper Issues
If the gradle-wrapper.jar is corrupted:
1. Delete `gradle/wrapper/gradle-wrapper.jar`
2. Download a fresh copy from the official Gradle repository
3. Ensure the file has execute permissions

### Missing Dependencies
If build fails due to missing dependencies:
1. Check your internet connection
2. Ensure Android SDK is properly installed
3. Verify all required SDK components are installed

### APK Size Issues
If APK is larger than 12MB:
1. Check build log for warnings
2. Ensure `minifyEnabled` and `shrinkResources` are set to `true`
3. Review dependencies for large libraries

## Final APK Features

The resulting APK includes:
- ✅ Tox network integration
- ✅ Dark and Hamtaro themes
- ✅ Spanish/German localization
- ✅ Japanese-style emojis
- ✅ Secret volleyball game
- ✅ 6-character Tox IDs
- ✅ Konami code detection
- ✅ Optimized for Sharp Keitai 4

Expected final size: **8-12MB**
