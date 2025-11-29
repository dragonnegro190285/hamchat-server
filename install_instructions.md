# 📱 Instalación de Ham-Chat APK

## 🚀 Método 1: Usando Android Studio (Recomendado)

### Pasos:
1. **Instalar Android Studio**
   - Descarga desde: https://developer.android.com/studio
   - Instala con SDK Android 9-14 (API 28-34)

2. **Abrir el Proyecto**
   ```
   File → Open → Selecciona la carpeta "tesis"
   ```

3. **Construir APK**
   ```
   Build → Build Bundle(s) / APK(s) → Build APK(s)
   ```

4. **Instalar en Dispositivo**
   ```
   Connect device via USB → Run app (▶️ button)
   ```

## 📦 Método 2: Build Manual (Sin Android Studio)

### Requisitos:
- JDK 8+ (ya tienes Java 25)
- Android SDK
- Gradle 8.1.2+

### Pasos:
```bash
# 1. Descargar Gradle Wrapper correcto
curl -L -o gradle/wrapper/gradle-wrapper.jar https://services.gradle.org/distributions/gradle-8.1.2-bin.zip

# 2. Extraer y configurar
unzip gradle-8.1.2-bin.zip
copy gradle-8.1.2/lib/gradle-wrapper-8.1.2.jar gradle/wrapper/

# 3. Construir APK
./gradlew assembleRelease
```

## 🔧 Método 3: Compilación Directa

Si tienes Android SDK instalado:

```bash
# Set environment variables
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.1.8-hotspot
set ANDROID_HOME=C:\Users\Admin\AppData\Local\Android\Sdk

# Build APK
gradle assembleRelease
```

## 📱 Instalación del APK

Una vez construido el APK:

1. **Habilitar Fuentes Desconocidas**
   ```
   Settings → Security → Install from unknown sources
   ```

2. **Instalar APK**
   ```
   adb install app/build/outputs/apk/release/app-release.apk
   ```

3. **O transferir archivo al dispositivo e instalar manualmente**

## 🎯 Características del APK Final

- **Nombre**: Ham-Chat
- **Paquete**: com.hamtaro.hamchat
- **Tamaño**: 8-12 MB (optimizado)
- **Compatible**: Android 9-16
- **Dispositivo**: Sharp Keitai 4

## 🐛 Si Falla la Construcción

1. **Verificar Java**: `java -version`
2. **Verificar Android SDK**: `adb version`
3. **Actualizar Gradle**: Descargar wrapper manualmente
4. **Limpiar proyecto**: `gradlew clean`

## 📞 Soporte

Si tienes problemas:
1. Revisa que Java esté en el PATH
2. Verifica instalación de Android SDK
3. Asegúrate de tener permisos de administrador

---

**¡Listo para probar Ham-Chat en tu Sharp Keitai 4! n.n/**
