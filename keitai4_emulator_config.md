# 📱 Sharp Keitai 4 Emulator Configuration

## 🎯 **Device Specifications**
- **Model**: Sharp Keitai 4
- **OS**: Android 9.0 Pie (API 28)
- **Processor**: Snapdragon 210 (MSM8909)
- **CPU**: Quad-core 1.1 GHz Cortex-A7
- **GPU**: Adreno 304
- **RAM**: 1 GB
- **Storage**: 8 GB (expandable via SD)
- **Screen**: 4.5 inches, 480 x 854 pixels, ~218 ppi
- **Weight**: 135g
- **Battery**: 1800 mAh

## 🔧 **Android Studio Emulator Setup**

### **Method 1: Custom Device**
1. **Open AVD Manager**
   - Tools → Device Manager → Create Device

2. **Hardware Profile**
   ```
   Device Name: Sharp Keitai 4
   Screen Size: 4.5 inches
   Resolution: 480 x 854
   Pixel Density: 240 dpi (mdpi)
   RAM: 1024 MB
   Internal Storage: 8000 MB
   SD Card: 2000 MB
   ```

3. **System Image**
   ```
   Target: Android 9.0 (Pie)
   API Level: 28
   ABI: x86_64 (for performance)
   Include Google APIs: Yes
   ```

4. **Advanced Settings**
   ```
   Graphics: Hardware - GLES 2.0+
   Multi-Core CPU: 2 cores
   Keyboard: Enabled
   Network: Wi-Fi enabled
   Camera: None (or Emulated)
   Sensors: Accelerometer, Gyroscope
   ```

### **Method 2: Use Existing Device**
```
Device: Nexus 5X
Modify Settings:
- RAM: Reduce to 1024 MB
- Storage: Set to 8000 MB
- Screen: Custom 480x854
- DPI: 240
```

## ⚙️ **Build Configuration**

### **Gradle Settings**
```gradle
android {
    compileSdk 34
    
    defaultConfig {
        minSdk 28  // Android 9.0 - Keitai 4
        targetSdk 34
        versionCode 1
        versionName "1.0"
        
        // Keitai 4 specific
        multiDexEnabled true
        vectorDrawables.useSupportLibrary = true
    }
    
    buildTypes {
        debug {
            minifyEnabled false
            debuggable true
        }
        
        release {
            minifyEnabled true
            shrinkResources true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
}
```

### **Dependencies for Keitai 4**
```gradle
dependencies {
    // Core components optimized for low-end devices
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.material:material:1.11.0'
    
    // Lightweight dependencies
    implementation 'androidx.multidex:multidex:2.0.1'
    implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
    
    // Tox4j for secure messaging
    implementation 'com.github.toktok:tox4j:0.2.2'
    
    // Minimal network and JSON
    implementation 'com.google.code.gson:gson:2.10.1'
    
    // Lightweight image loading
    implementation 'com.github.bumptech.glide:glide:4.16.0'
}
```

## 🎮 **Performance Optimizations for Keitai 4**

### **Memory Management**
```kotlin
// Enable large heap support
<application
    android:largeHeap="true"
    android:hardwareAccelerated="true">
```

### **Layout Optimizations**
```xml
<!-- Use lightweight layouts -->
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">
    
    <!-- Lightweight components -->
    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Ham-Chat" />
        
</LinearLayout>
```

### **Performance Settings**
```kotlin
// Optimize for low-end devices
override fun onCreate(savedInstanceState: Bundle?) {
    // Enable hardware acceleration
    window.setFlags(
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    )
    
    // Optimize memory
    System.gc()
    
    super.onCreate(savedInstanceState)
}
```

## 📱 **Testing on Keitai 4 Emulator**

### **Performance Tests**
1. **Startup Time**: < 3 seconds
2. **Memory Usage**: < 200 MB
3. **Battery Usage**: < 5% per hour
4. **Storage**: < 50 MB APK size

### **Functionality Tests**
- ✅ **Tox Connection**: Stable
- ✅ **Message Sending**: < 2 seconds
- ✅ **Theme Switching**: Smooth
- ✅ **Secret Game**: Responsive
- ✅ **Emojis**: Display correctly

### **Compatibility Tests**
- ✅ **Android 9.0**: Full compatibility
- ✅ **480x854 Resolution**: Optimized
- ✅ **1GB RAM**: Smooth performance
- ✅ **Snapdragon 210**: Hardware acceleration

## 🚀 **Deployment Instructions**

### **For Physical Keitai 4**
1. **Enable USB Debugging**
2. **Connect via ADB**
3. **Install APK**: `adb install ham-chat.apk`
4. **Grant Permissions**: Internet, Storage (if needed)

### **For Emulator Testing**
1. **Start Keitai 4 AVD**
2. **Drag APK to emulator**
3. **Install and test**
4. **Monitor performance**

## 🎯 **Recommended Android Studio Version**

### **Minimum Required**
- **Android Studio Arctic Fox** (2020.3.1)
- **JDK 8** or higher
- **4GB RAM** minimum
- **2GB free disk space**

### **Recommended**
- **Android Studio Giraffe** (2022.3.1) or newer
- **JDK 11** or higher
- **8GB RAM** recommended
- **4GB free disk space**

### **Download Links**
- **Official**: https://developer.android.com/studio
- **System Requirements**: https://developer.android.com/studio#requirements

## 🔍 **Troubleshooting**

### **Common Issues**
1. **Slow Emulator**: Use x86_64 image, enable hardware acceleration
2. **Out of Memory**: Increase AVD RAM to 1024MB
3. **Graphics Issues**: Enable GPU acceleration
4. **Network Issues**: Check ADB connection

### **Solutions**
```bash
# Fix slow emulator
$ emulator -avd Keitai4 -gpu host

# Increase memory
$ emulator -avd Keitai4 -memory 1024

# Enable hardware acceleration
$ emulator -avd Keitai4 -accel on
```

---
**Configuration optimized for Sharp Keitai 4 performance**
**Last updated**: 26/11/2025
