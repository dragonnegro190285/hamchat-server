# 🚀 Android Studio Optimization for 8GB RAM

## 🎯 Settings for Your Intel i5-8250U + 8GB RAM

### ⚙️ Android Studio VM Options
Edit `studio64.exe.vmoptions` in Android Studio installation folder:

```
-Xms1024m
-Xmx2048m
-XX:ReservedCodeCacheSize=256m
-XX:+UseG1GC
-XX:SoftRefLRUPolicyMSPerMB=50
-ea
-Dsun.io.useCanonCaches=false
-Djava.net.preferIPv4Stack=true
-Djdk.http.auth.tunneling.disabledSchemes=""
-XX:+HeapDumpOnOutOfMemoryError
-XX:-OmitStackTraceInFastThrow
```

### 📱 Emulator Configuration
Create lightweight AVD:
- **Device**: Pixel 4a (smaller footprint)
- **RAM**: 2048 MB (2GB)
- **Internal Storage**: 4000 MB (4GB)
- **Graphics**: Hardware - GLES 2.0
- **CPU Cores**: 2
- **Boot Animation**: Disabled

### 🔧 Performance Settings
1. **File → Settings → Appearance & Behavior → System Settings**
   - ✅ Power Save Mode (when compiling)
   - ❌ Unused files: Hide

2. **File → Settings → Build, Execution, Deployment → Compiler**
   - ✅ Build process: In separate process
   - ✅ Compile independent modules in parallel

3. **File → Settings → Editor → General**
   - ✅ Power Save Mode (when not editing)

### 🛡️ Windows 11 Optimizations
1. **Close unnecessary apps** while developing
2. **Set Android Studio** to "High Performance" in Windows Graphics Settings
3. **Disable Windows animations** while compiling

### 📊 Memory Usage Expected
- **Android Studio**: ~2GB
- **Emulator**: ~2GB  
- **System**: ~4GB
- **Total**: ~8GB (perfect fit!)

## 🎯 Recommended Workflow

### 📋 Development Steps
1. **Open Ham-Chat project**
2. **Wait for Gradle sync** (first time only)
3. **Create lightweight AVD**
4. **Build and test**
5. **Close emulator** when not testing

### ⚡ Performance Tips
- **Use physical device** if possible (faster than emulator)
- **Enable Power Save Mode** when not actively coding
- **Close unused tabs** in Android Studio
- **Restart Android Studio** if it becomes slow

## 🎉 Your PC is Perfect for Ham-Chat Development!

### ✅ What You Can Do
- ✅ **Compile Ham-Chat** quickly
- ✅ **Run emulator** smoothly  
- ✅ **Test all features**
- ✅ **Generate APK** efficiently
- ✅ **Debug issues** without lag

### 🚀 Expected Performance
- **Build time**: 1-2 minutes
- **Emulator boot**: 2-3 minutes
- **App launch**: <30 seconds
- **Memory usage**: 6-7GB peak

Your Intel i5-8250U with 8GB RAM is **perfect for Ham-Chat development!**
