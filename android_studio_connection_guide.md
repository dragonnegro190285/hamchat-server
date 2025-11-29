# 🚀 Guía de Conexión Android Studio + Ham-Chat

## 🔗 **Paso a Paso para Configurar Ham-Chat**

### 📥 **Paso 1: Instalar Android Studio**
1. **Descargar**: https://developer.android.com/studio
2. **Instalar**: Windows 64-bit version
3. **Configurar**: Standard installation
4. **Iniciar**: Android Studio

### 📂 **Paso 2: Abrir Proyecto Ham-Chat**
1. **Android Studio** → **Open**
2. **Navegar**: `c:\Users\Admin\Desktop\tesis\`
3. **Seleccionar**: carpeta `tesis`
4. **Esperar**: Gradle sync (primer vez puede tardar)

### ⚙️ **Paso 3: Configurar para tu Hardware**
```
File → Settings → Appearance & Behavior → System Settings
Memory Settings: 2048MB

File → Settings → Build, Execution, Deployment → Compiler
Command-line Options: -Xmx2048m -XX:MaxPermSize=512m
```

### 📱 **Paso 4: Configurar Emulador o Dispositivo Real**

#### **Opción A: Emulador Ligero**
1. **Tools → AVD Manager**
2. **Create Virtual Device**
3. **Pixel 4a** (más ligero)
4. **Android 10 (API 29)** - igual a tu dispositivo
5. **RAM**: 2048MB
6. **Storage**: 4000MB
7. **Graphics**: Hardware - GLES 2.0

#### **Opción B: Conectar tu Android 10 Real**
1. **USB Debugging** en tu celular:
   ```
   Settings → About Phone → Tap "Build number" 7 times
   Settings → Developer Options → USB Debugging: ON
   ```
2. **Conectar** celular con USB
3. **Permitir** debugging en el celular
4. **Seleccionar** dispositivo en Android Studio

### 🔧 **Paso 5: Build Configuration**
1. **Verificar** build.gradle:
   - minSdk: 29 (Android 10)
   - targetSdk: 34
   - compileSdk: 34

2. **Sync Project**: File → Sync Project with Gradle Files

### 🎯 **Paso 6: Build y Run**
1. **Build**: Build → Build Bundle(s) / APK(s) → Build APK(s)
2. **Run**: Click botón verde (▶️)
3. **Seleccionar**: Emulador o tu dispositivo real
4. **Esperar**: Instalación y lanzamiento

### 🐹 **Paso 7: Probar Ham-Chat**
#### **🌟 Splash Screen:**
- ✅ Fuente Alice in Wonderland visible
- ✅ Gradiente mágico funcionando
- ✅ Animaciones suaves

#### **📱 Interfaz Principal:**
- ✅ Fuente Gothic Book en textos
- ✅ Botones funcionales
- ✅ Layout responsive

#### **🎤 Audio:**
- ✅ Grabación Opus 48kbps
- ✅ Reproducción sin errores
- ✅ Validación de formatos

#### **📞 Tonos:**
- ✅ Solo archivos TTA aceptados
- ✅ Calidad premium

#### **🛡️ Seguridad:**
- ✅ Sin crashes
- ✅ Logging seguro
- ✅ Validaciones activas

## 🎛️ **Panel de Android Studio - Qué Verás:**

### 📊 **Project Structure:**
```
app/
├── src/main/
│   ├── java/com/hamtaro/hamchat/
│   │   ├── ui/SimpleMediaActivity.kt ✅
│   │   ├── multimedia/SimpleMediaManager.kt ✅
│   │   └── security/SecurityManager.kt ✅
│   ├── res/
│   │   ├── font/gothic_book.ttf ✅
│   │   ├── font/alice_in_wonderland.ttf ✅
│   │   ├── layout/activity_simple_media.xml ✅
│   │   └── layout/activity_splash.xml ✅
│   └── AndroidManifest.xml ✅
└── build.gradle ✅
```

### 🔧 **Build Output:**
```
BUILD SUCCESSFUL in 1m 30s
32 actionable tasks: 32 executed
```

### 📱 **Logcat:**
```
I/HamChat: SplashActivity started
I/HamChat: Gothic Book font loaded
I/HamChat: Alice in Wonderland font loaded
I/HamChat: SimpleMediaActivity created
I/HamChat: Opus 48kbps recording started
```

## 🎯 **Troubleshooting Común:**

### ❌ **Gradle Sync Error:**
```
File → Invalidate Caches / Restart → Invalidate and Restart
```

### ❌ **SDK Missing:**
```
Tools → SDK Manager → Install Android 10 (API 29)
```

### ❌ **Device Not Connected:**
```
- Verificar USB Debugging activo
- Reinstalar drivers USB
- Reiniciar ADB: adb kill-server && adb start-server
```

### ❌ **Build Error:**
```
Build → Clean Project → Build → Rebuild Project
```

## 🎉 **¡Listo para Ham-Chat!**

### ✅ **Cuando veas esto, está todo listo:**
- ✅ BUILD SUCCESSFUL
- ✅ APK generado
- ✅ App instalada en dispositivo
- ✅ Ham-Chat funcionando perfectamente

### 🐹 **Disfruta tu app:**
- 🎤 Audio Opus 48kbps cristalino
- 📞 Tonos TTA premium
- 📖 Fuentes profesionales
- 🛡️ Seguridad completa
- ⚡ Rendimiento óptimo

**¡Tu Sharp Keitai 4 con Android 10 correrá Ham-Chat perfectamente!** 📱✨🐹
