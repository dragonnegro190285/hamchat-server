# 🚀 Guía de Instalación Android Studio para Ham-Chat

## 📱 **Recomendación Oficial**

### ✅ **Usar Android Studio Standard**
- **Versión**: Giraffe 2022.3.1 o más nueva
- **Tipo**: Standard Installation
- **Motivo**: Mejor compatibilidad con Sharp Keitai 4

### ❌ **Evitar Android Studio Custom con Android 16**
- **Problema**: Demasiado nuevo para Keitai 4
- **Incompatibilidad**: API 34+ vs API 28
- **Riesgos**: Build failures, runtime errors

## 🔧 **Pasos de Instalación**

### **1. Descargar Android Studio Standard**
```
URL: https://developer.android.com/studio
Seleccionar: Android Studio (no Android Studio preview)
Sistema: Windows x64 (para tu PC)
```

### **2. Ejecutar Instalador**
```
✅ Standard installation (recomendado)
✅ Android Virtual Device (para emulador)
✅ Android SDK (seleccionar Android 9.0)
✅ Performance (Intel HAXM si CPU Intel)
✅ Android SDK Platform-Tools
```

### **3. Configuración Post-Instalación**
```
1. Iniciar Android Studio
2. Skip setup (o configurar básico)
3. File → Settings → Appearance & Behavior → System Settings → Android SDK
4. Verificar: Android 9.0 (Pie, API Level 28) instalado
```

## 📱 **Configurar SDK para Keitai 4**

### **SDK Platforms Requeridas:**
```
✅ Android 9.0 (Pie, API Level 28) - Principal
✅ Android 10.0 (Q, API Level 29) - Opcional
✅ Android SDK Build-Tools 28.0.3
✅ Android SDK Platform-Tools 33.0.3
✅ Android SDK Tools 25.2.5
```

### **SDK Tools Requeridas:**
```
✅ Android SDK Build-Tools
✅ Android SDK Platform-Tools
✅ Android SDK Command-line Tools
✅ Android Emulator
✅ Intel x86 Emulator Accelerator (HAXM installer)
```

## 🎮 **Crear Emulador Sharp Keitai 4**

### **Opción 1: Custom Device (Recomendado)**
```
1. Tools → Device Manager → Create Device
2. Hardware Profile: Create Custom
3. Device Name: Sharp Keitai 4
4. Screen Size: 4.5 inches
5. Resolution: 480 x 854 pixels
6. Pixel Density: 240 dpi (mdpi)
7. RAM: 1024 MB
8. Internal Storage: 8000 MB
9. SD Card: 2000 MB
10. Processor: x86_64 (para rendimiento)
```

### **Opción 2: Modificar Nexus 5X**
```
1. Tools → Device Manager → Create Device
2. Phone: Nexus 5X
3. System Image: Android 9.0 (API 28)
4. Advanced Settings:
   - RAM: 1024 MB (reducir de 2048)
   - Storage: 8000 MB (reducir de 32000)
   - Screen: Custom 480x854
   - DPI: 240
```

### **System Image:**
```
Target: Android 9.0 (Pie)
API Level: 28
ABI: x86_64 (para mejor rendimiento)
Include Google APIs: Yes
```

## 🚀 **Abrir Proyecto Ham-Chat**

### **Pasos Iniciales:**
```
1. Iniciar Android Studio
2. File → Open (o Open Project)
3. Navegar a: C:\Users\Admin\Desktop\tesis
4. Esperar Gradle sync (5-10 minutos)
5. Verificar: Sin errores en build.gradle
```

### **Configuración del Proyecto:**
```
File → Project Structure:
- SDK Location: Usar instalación por defecto
- Project: Language Level: Kotlin 1.9
- Modules: app - Compile Sdk Version: API 28
- Build Tools: 28.0.3
```

## ⚡ **Build y Ejecución**

### **Build del APK:**
```
1. Build → Clean Project
2. Build → Rebuild Project  
3. Build → Build APK(s)
4. Esperar: 2-3 minutos
5. APK generado en: app/build/outputs/apk/debug/app-debug.apk
```

### **Run en Emulador:**
```
1. Iniciar emulador Keitai 4
2. Select Device: Keitai 4 Emulator
3. Click botón verde Run (▶️)
4. Esperar instalación: 30 segundos
5. App se inicia automáticamente
```

### **Run en Dispositivo Real:**
```
1. Conectar Sharp Keitai 4 via USB
2. Verificar: adb devices muestra dispositivo
3. Select Device: Sharp Keitai 4
4. Click botón verde Run (▶️)
5. Instalación automática
```

## 🔧 **Troubleshooting Común**

### **Gradle Sync Issues:**
```
Problema: "Gradle sync failed"
Solución: 
- File → Invalidate Caches / Restart
- Delete .gradle folder en proyecto
- Reabrir proyecto
```

### **SDK Issues:**
```
Problema: "SDK not found"
Solución:
- File → Settings → Android SDK
- Install Android 9.0 (API 28)
- Apply y OK
```

### **Emulator Issues:**
```
Problema: "Emulator slow/crashes"
Solución:
- Enable hardware acceleration (BIOS)
- Use x86_64 image
- Increase RAM to 1024MB
- Enable GPU acceleration
```

### **Build Errors:**
```
Problema: "Build failed"
Solución:
- Build → Clean Project
- File → Sync Project with Gradle Files
- Verificar dependencias en build.gradle
```

## 📱 **Optimizaciones para Keitai 4**

### **Performance Settings:**
```
File → Settings → Build, Execution, Deployment → Compiler:
- Command-line Options: --max-workers=2
- Build process heap size: 2048 MB
- Gradle VM options: -Xmx2048m
```

### **Emulator Performance:**
```
Tools → AVD Manager → Edit AVD:
- Graphics: Hardware - GLES 2.0+
- Multi-Core CPU: 2 cores
- RAM: 1024 MB
- Boot option: Cold boot
```

## 🎯 **Verification Checklist**

### **Antes de Build:**
```
✅ Android Studio Standard instalado
✅ SDK Android 9.0 disponible
✅ Emulador Keitai 4 creado
✅ Proyecto Ham-Chat abierto
✅ Gradle sync sin errores
✅ Dependencias actualizadas
```

### **Después de Build:**
```
✅ APK generado exitosamente
✅ Size: 6-8 MB (optimizado)
✅ Instalación en emulador funciona
✅ App inicia sin crashes
✅ Todas las features funcionan
✅ Sharp Keitai 4 conectado
```

## 🚀 **Timeline Estimado**

### **Instalación Android Studio:**
- Download: 5-10 minutos
- Installation: 10-15 minutos
- Setup inicial: 5 minutos

### **Configuración Proyecto:**
- SDK setup: 5-10 minutos
- Emulator creation: 5 minutos
- Gradle sync: 5-10 minutos

### **Build y Test:**
- Clean build: 2-3 minutos
- APK generation: 1 minuto
- Emulator test: 5 minutos
- Device install: 30 segundos

**Total: 45-60 minutos**

---

**¡Listo para instalar Android Studio y compilar Ham-Chat!** 🐹📱✨

Usa la versión Standard para máxima compatibilidad con tu Sharp Keitai 4.
