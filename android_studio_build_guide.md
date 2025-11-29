# 📱 Guía de Construcción de Ham-Chat APK con Android Studio

## 🚀 Pasos para Construir el APK

### 1. Abrir Proyecto en Android Studio
```
File → Open → Seleccionar carpeta "tesis"
```

### 2. Sincronizar Proyecto
- Android Studio detectará automáticamente el proyecto
- Espera a que termine la sincronización de Gradle
- Si pide actualizar Gradle, acepta

### 3. Construir APK Debug (Para pruebas)
```
Build → Build Bundle(s) / APK(s) → Build APK(s)
```

### 4. Construir APK Release (Final optimizado)
```
Build → Generate Signed Bundle / APK → APK → Create new...
```

**Configuración de Firma:**
- Key store path: Crear nuevo archivo `.jks`
- Password: Elige una contraseña
- Alias: `hamchat`
- Key password: Misma contraseña

### 5. Instalar en Sharp Keitai 4

#### Método A: USB
1. Conectar Keitai 4 via USB
2. Habilitar "Depuración USB"
3. Click en botón "Run" (▶️) en Android Studio

#### Método B: APK Manual
1. APK generado en: `app/build/outputs/apk/release/app-release.apk`
2. Transferir al dispositivo
3. Instalar manualmente

## 🔧 Configuración Rápida

### Si hay problemas de sincronización:

1. **Actualizar Gradle Wrapper**
   ```
   File → Invalidate Caches / Restart → Invalidate and Restart
   ```

2. **Verificar SDK**
   ```
   File → Project Structure → SDK Location
   ```

3. **Build Variants**
   ```
   Build → Select Build Variant → release
   ```

## 📱 Características del APK Final

- **Nombre**: Ham-Chat
- **Paquete**: com.hamtaro.hamchat  
- **Tamaño**: ~8-12 MB
- **Compatible**: Android 9-16
- **Optimizado**: Para Sharp Keitai 4 (1GB RAM)

## 🎯 Verificación de Funcionalidades

Una vez instalado, prueba:

1. ✅ **Mensajería Tox**: Crear cuenta y agregar amigos
2. ✅ **IDs de 6 caracteres**: Verificar display corto
3. ✅ **Tema oscuro**: Interfaz predeterminada
4. ✅ **Tema Hamtaro**: Escribe "Mirania Du bist zartlich >////<"
5. ✅ **Emojis japoneses**: Prueba n.n, u.u, x.xU, etc.
6. ✅ **Juego secreto**: Código Konami en login
7. ✅ **Idiomas**: Cambiar español/alemán

## 🐛 Solución de Problemas

### Build Fallido:
```
Build → Clean Project
Build → Rebuild Project
```

### Gradle Sync Error:
```
File → Sync Project with Gradle Files
```

### APK No Instala:
- Habilitar "Fuentes desconocidas"
- Verificar compatibilidad Android 9+

## 📞 Listo para Probar

Una vez construido el APK, tendrás:
- **Ham-Chat** funcionando en tu Sharp Keitai 4
- Todas las características especiales implementadas
- Optimizado para 1GB RAM y Snapdragon 210

¡Disfruta de tu app de mensajería con Hamtaro! n.n/
