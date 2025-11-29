# Ham-Chat 🐹

Una aplicación de mensajería segura para Sharp Keitai 4 con características especiales inspiradas en Hamtaro.

## Características Principales

### 📱 Mensajería P2P con Servidor Embebido
- **Cada dispositivo es cliente Y servidor** - No necesitas servidor externo
- Servidor HTTP embebido (NanoHTTPD) en puerto 8080
- Comunicación directa entre dispositivos en la misma red WiFi
- **Sin duplicación de mensajes** - Usa `since_id` para carga incremental
- Compatible con servidor en la nube (Render) como respaldo

### 🎨 Interfaz Personalizada
- **Tema Oscuro**: Interfaz elegante y fácil para la vista
- **Tema Hamtaro**: Se activa escribiendo "Mirania Du bist zartlich >////<" en el chat
- Colores especiales: naranja, crema y negro inspirados en Hamtaro
- Compatible con Android 9 - 16

### 😊 Emojis Japoneses
Soporte para emojis estilo japonés:
- `n.n` -> 😊
- `u.u` -> 😢
- `x.xU` -> 😵
- `._.U` -> 😐
- `*O*` -> 😮
- Y muchos más...

### 🌍 Idiomas
- Español (predeterminado)
- Alemán
- Cambio dinámico sin reiniciar la aplicación

### 🎮 Juego Secreto: Voleibol Hamtaro
**Acceso**: Código Konami en pantalla de login
- **Keitai**: ↑↑↓↓←→22
- **Otros teléfonos**: ↑↑↓↓←→BA

#### Características del Juego
- **3 intentos** por partida
- **Sistema de puntuación** progresivo
- **Limpieza de faltas** en: 200, 500, 1000, 1600, 2000 puntos
- **Mensaje especial** a 1000 puntos: "Hecho por Hamtaro y Mirania con Liebe <3"
- **2 modos de dificultad**:
  - **A (Fácil)**: Máximo 3 pelotas, velocidad constante
  - **B (Progresiva)**: 4 pelotas, velocidad incrementa gradualmente

## 📋 Requisitos del Sistema

### Hardware Compatible
- **Sharp Keitai 4**
- **Procesador**: Qualcomm Snapdragon 210 Quad-core 1.1GHz
- **RAM**: 1GB
- **Android**: 9.0 (API 28) - 14.0 (API 34)

### Espacio de Almacenamiento
- **Tamaño APK**: 8-12 MB (optimizado)
- **Espacio adicional**: ~5 MB para datos y caché

## 🔧 Instalación

1. Descarga el APK desde la fuente oficial
2. Habilita "Instalación desde fuentes desconocidas" en ajustes
3. Instala el APK
4. Abre la aplicación y crea tu cuenta

## 🚀 Configuración Inicial

1. **Crear cuenta**: Ingresa nombre de usuario y contraseña
2. **Obtener ID Tox**: Tu ID único de 6 caracteres se genera automáticamente
3. **Agregar amigos**: Intercambia IDs con tus contactos
4. **Comienza a chatear**: Mensajería segura y privada

## 🎯 Características Especiales

### Activación del Tema Hamtaro
1. Abre cualquier chat
2. Escribe exactamente: `Mirania Du bist zartlich >////<`
3. La interfaz cambiará automáticamente a los colores de Hamtaro

### Acceso al Juego Secreto
1. Ve a la pantalla de login
2. Ingresa nombre de usuario
3. Ingresa la secuencia:
   - **Keitai**: ↑↑↓↓←→22
   - **Otros**: ↑↑↓↓←→BA
4. ¡Disfruta del juego de voleibol!

## 🔒 Privacidad y Seguridad

- **Sin servidores**: Comunicación directa P2P
- **Encriptación end-to-end**: Protección total de mensajes
- **Sin recolección de datos**: Tu privacidad es nuestra prioridad
- **Código abierto**: Transparencia total

## 🐛 Soporte y Problemas

### Problemas Comunes
- **Conexión**: Verifica tu conexión a internet
- **Notificaciones**: Asegúrate de tener permisos habilitados
- **Rendimiento**: Cierra otras aplicaciones en Keitai 4

### Contacto de Soporte
- Reporta problemas en GitHub Issues
- Comunidad en Telegram

## 📝 Notas de Desarrollo

### Optimización para Keitai 4
- Interfaz optimizada para pantalla pequeña
- Uso eficiente de memoria (1GB RAM)
- Animaciones suaves con hardware limitado
- Consumo mínimo de batería

### Características Técnicas
- **Kotlin**: Lenguaje moderno y eficiente
- **Tox4j**: Implementación Java de Tox
- **Material Design 3**: UI moderna y accesible
- **Arquitectura MVVM**: Código mantenible y escalable

## 📜 Licencia

Proyecto bajo licencia MIT - Código abierto para la comunidad.

---

**Hecho con ❤️ por Hamtaro y Mirania con Liebe**
