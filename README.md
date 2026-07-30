# UCF VPN Android

Aplicación Android que establece un stacked VPN (SSTP → Portal Auth → wstunnel → WireGuard) para tunelizar todo el tráfico del dispositivo a través de la infraestructura de red de la UCF.

> **⚠️ Requisito**: Esta app está diseñada para funcionar dentro de la red de la UCF o con acceso al servidor SSTP de la universidad (`npv.ucf.edu.cu`).

## Arquitectura

```
┌──────────────────────────────────────────────────────┐
│                    Apps Android                       │
│                        │                             │
│                        ▼                             │
│            VpnService (TUN fd)                       │
│                        │                             │
│                        ▼                             │
│              WireGuard (GoBackend)                   │
│           endpoint → 127.0.0.1:51820                 │
│                        │                             │
│                        ▼                             │
│         wstunnel (WebSocket → UDP forward)           │
│      wss://solverius-ws.zpwhqo.easypanel.host        │
│                        │                             │
│                        ▼                             │
│    SSTP Tunnel (socket protegido con protect())      │
│      npv.ucf.edu.cu:443 (SSL/TLS)                    │
│                        │                             │
│                        ▼                             │
│    Proxy Auth (Portal Cautivo UCF)                   │
│      internet.ucf.edu.cu (CSRF login)                │
│                        │                             │
└────────────────────────┼─────────────────────────────┘
                         │
               Red Física (WiFi/4G)
                         │
                         ▼
              ┌─────────────────────┐
              │   SSTP Server (UCF)  │
              │  npv.ucf.edu.cu:443  │
              └─────────┬───────────┘
                        │
              ┌─────────▼───────────┐
              │  wstunnel Server     │
              │  (solverius-ws...)   │
              └─────────┬───────────┘
                        │
              ┌─────────▼───────────┐
              │  WireGuard Server    │
              │  72.62.160.61:51820  │
              └─────────────────────┘
                        │
                   Internet 🌐
```

## Stack Tecnológico

| Componente        | Tecnología                              |
| ----------------- | --------------------------------------- |
| **Lenguaje**      | Kotlin                                  |
| **UI**            | Jetpack Compose + Material 3            |
| **VPN**           | Android VpnService API                  |
| **SSTP**          | Implementación propia (MS-SSTP spec)    |
| **WireGuard**     | `com.wireguard.android:tunnel` (.aar)   |
| **wstunnel**      | Binary ARM64 embebido en assets/        |
| **Proxy Auth**    | OkHttp + CSRF flow                      |
| **Estado**        | StateFlow + Máquina de estados (11+4)   |
| **Seguridad**     | Android Keystore (AES-256/GCM)          |
| **Build**         | GitHub Actions                          |
| **Mínimo SDK**    | API 26 (Android 8.0)                    |

## Requisitos

- Android 8.0+ (API 26)
- Credenciales de acceso a VPN UCF
- Servidor wstunnel operativo (por defecto: `solverius-ws.zpwhqo.easypanel.host`)
- Servidor WireGuard operativo (por defecto: `72.62.160.61:51820`)

## Configuración

La app permite configurar:

1. **SSTP**: Servidor, puerto, usuario, contraseña
2. **Proxy**: Host, puerto, usuario, contraseña (portal cautivo)
3. **wstunnel**: Modo fijo (predefinido) o dinámico (todos los parámetros)
4. **WireGuard**: Endpoint, IP local, DNS, claves (almacenadas en Keystore)

## Build Local

```bash
# Clonar
git clone <repo-url>
cd ucf-vpn-android

# Build APK debug
./gradlew assembleDebug

# Ejecutar tests
./gradlew test

# Tests de integración (requiere emulador)
./gradlew connectedCheck
```

## CI/CD (GitHub Actions)

El workflow `.github/workflows/build.yml` ejecuta:

1. **Build + Unit Tests**: `./gradlew build test`
2. **UI Tests**: Emulador API 29
3. **APK Build**: Sube APK debug como artifact

## Funcionalidades

- ✅ Stack VPN completo (SSTP → Auth → wstunnel → WireGuard → VpnService)
- ✅ Reconexión automática con backoff exponencial
- ✅ Ignorar advertencias de certificados SSL
- ✅ Login automático de portal cautivo (CSRF)
- ✅ Modos wstunnel: fijo y dinámico
- ✅ Almacenamiento seguro de claves (Android Keystore)
- ✅ UI con estados de conexión en tiempo real
- ✅ Logs internos con clasificación de errores
- ✅ Sin root

## Limitaciones (V1)

- ❌ No hay split-tunneling (todo el tráfico va por VPN)
- ❌ No hay kill switch
- ❌ No hay múltiples perfiles
- ❌ No auto-start on boot

## Licencia

MIT
