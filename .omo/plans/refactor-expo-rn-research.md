# Investigación: Refactorización a Expo React Native — SSTP + wstunnel + WireGuard

## TL;DR

> **Objetivo**: Migrar la app Android nativa (Kotlin + Jetpack Compose) a Expo React Native, manteniendo la lógica nativa de VPN (SSTP, wstunnel, WireGuard) como **Expo Modules** en Kotlin. La UI se desarrolla en TypeScript/React Native, la lógica nativa se expone via Expo Modules API.

> **Hallazgo clave**: VPN en Android **requiere** código nativo (VpnService, SSL sockets, WireGuard GoBackend). No hay forma de implementar SSTP/WireGuard puros desde JavaScript. La arquitectura correcta es: **Expo RN para UI + Kotlin Expo Modules para VPN nativo**.

---

## 1. Estado Actual del Código

### Arquitectura existente (Kotlin puro)
```
app/src/main/java/com/ucfvpn/app/
├── sstp/           # Cliente SSTP (protocolo, handshake, PPP)
│   ├── client/     # SstpTunnel, SstpHandshake, SstpTunnelImpl
│   ├── protocol/   # SstpProtocol, SstpPacket, SstpControlPacket
│   └── ppp/        # PppHandler, HDLCHandler (FCS-16, HDLC framing)
├── proxy/          # ProxyAuthService (CSRF login portal cautivo)
├── wstunnel/       # WstunnelManager (binario ARM64 + ProcessBuilder)
├── vpn/            # VpnGatewayService, WireGuardManager, VpnConfig
├── wg/             # WireGuardConfig, Parser, Repository (Keystore)
├── orchestrator/   # VpnOrchestrator (secuencia completa)
├── state/          # VpnState, VpnStateMachine, ReconnectManager
├── ui/             # Jetpack Compose (ViewModel, NavGraph, Screens)
└── logging/        # VpnLogger, LogRepository
```

### Lo que funciona
- SSTP: Protocolo completo en Kotlin puro (sin NDK)
- PPP: HDLC framing con FCS-16 (sin lwIP/NDK)
- Proxy Auth: Login CSRF con OkHttp
- wstunnel: Binario ARM64 embebido + ProcessBuilder
- WireGuard: .aar oficial (GoBackend)
- VpnService: TUN interface + protect() para anti-loop
- Estado: Máquina de 11 estados + reconexión automática
- UI: Jetpack Compose con 3 pantallas

### Lo que NO funciona (tests rotos)
- Todos los tests están en `test-broken/` (nunca pasaron por falta de Gradle)
- La app nunca compiló exitosamente (Gradle download timeout)

---

## 2. Investigación: SSTP para Android

### 2.1 Implementaciones existentes

| Proyecto | Lenguaje | Estado | Notas |
|----------|----------|--------|-------|
| `kittoku/Open-SSTP-Client` | Kotlin | ⭐538 | App completa, usa VpnService + lwIP (NDK) para PPP |
| `eclabsys/sstp-client-android` | Kotlin | Abandonado | Fork de Open-SSTP-Client |
| **Nuestro código** | Kotlin | Parcial | Protocolo + handshake funcional, PPP incompleto |

### 2.2 Enfoque Kotlin/JVM puro (SIN NDK)

**SÍ es posible** implementar SSTP en Kotlin puro:
- ✅ SSL/TLS socket: `javax.net.ssl.SSLContext` + `SSLSocket`
- ✅ HTTP SSTP_DUPLEX_POST: String concatenation + socket write
- ✅ SSTP control messages: ByteBuffer pack/unpack
- ✅ Crypto Binding: `javax.crypto.Mac` (HMAC-SHA1), `MessageDigest` (SHA-1)
- ✅ Export keying material: `SSLSession.exportKeyingMaterial()` (reflexión en Android)

**El problema es PPP**, no SSTP:
- ❌ Android no tiene `pppd` en la mayoría de dispositivos
- ❌ No hay API Kotlin/JVM para crear interfaces PPP
- ❌ lwIP requiere NDK (C/C++ compilation)

### 2.3 Solución: PPP sobre TUN (sin PPP real)

**Enfoque recomendado**: No usar PPP. En su lugar:
1. SSTP establece la conexión SSL/TLS y autentica
2. En lugar de frames PPP, usar SSTP como **transporte TCP puro**
3. Abrir un socket TCP sobre la conexión SSTP
4. Sobre ese socket, hacer HTTP al portal cautivo
5. Sobre ese socket, crear el WebSocket tunnel

**Alternativa**: Usar `tun2socks` (go-tun2socks) para convertir TUN → SOCKS5, y SSTP como transporte SOCKS5. Esto requiere NDK pero es más estándar.

### 2.4 Crypto Binding en Android

```kotlin
// exportKeyingMaterial funciona en Android via reflexión
val session = sslSocket.session
val method = session.javaClass.getMethod(
    "exportKeyingMaterial",
    String::class.java,
    Array<ByteArray>::class.java,
    Int::class.javaPrimitiveType
)
val mk = method.invoke(session, "SSTP Key Binding", null, 32) as ByteArray
```

**Verificado**: Este enfoque ya está implementado en nuestro código actual (`SstpHandshake.kt` línea 307-323).

---

## 3. Investigación: wstunnel (WebSocket Tunnel)

### 3.1 wstunnel actual (erebe/wstunnel)

- **Última versión**: v10.x (Rust rewrite desde v7.0.0)
- **Binarios**: Estáticos disponibles para `linux-arm64` (Android compatible)
- **Comando clave**:
  ```bash
  wstunnel client \
    -L udp://51820:REMOTE_HOST:51820?timeout_sec=0 \
    -p http://PROXY:3128 \
    wss://SERVER \
    --connection-retry-max-backoff 10s \
    --websocket-ping-frequency 10s
  ```

### 3.2 Enfoque actual (binario embebido)

**Funciona** pero tiene problemas:
- ✅ Binario ARM64 estático (musl) ejecuta sin root
- ❌ ProcessBuilder en Android tiene limitaciones de batería
- ❌ El proceso puede ser matado por el sistema en background
- ❌ Ocupa ~15MB de espacio en disco
- ❌ No hay forma nativa de monitorear el estado del tunnel

### 3.3 Alternativa: Kotlin puro con OkHttp WebSocket

**SÍ es posible** implementar un WebSocket tunnel en Kotlin:

```kotlin
// OkHttp tiene soporte WebSocket nativo
val client = OkHttpClient.Builder()
    .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("PROXY", 3128)))
    .build()

val request = Request.Builder()
    .url("wss://server")
    .build()

val ws = client.newWebSocket(request, object : WebSocketListener() {
    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        // Recibir paquetes UDP del servidor remoto
        udpSocket.send(DatagramPacket(bytes.toByteArray(), ...))
    }
})
```

**Ventajas del enfoque Kotlin puro**:
- Sin binario embebido (reduce APK ~15MB)
- Control total del ciclo de vida
- Mejor integración con Android (battery optimization, foreground service)
- Logs integrados con Timber
- Sin dependencia de ProcessBuilder

**Desventajas**:
- Reimplementar protocolo wstunnel (no trivial)
- wstunnel tiene optimizaciones complejas (connection pooling, multiplexing)
- El protocolo WebSocket tiene overhead vs el binario optimizado en Rust

### 3.4 Recomendación

**Mantener el binario** como opción principal, pero encapsularlo mejor:
- Crear Expo Module que gestione el proceso
- Agregar health checks periódicos
- Implementar restart automático
- Opcionalmente: implementar WebSocket tunnel Kotlin puro como alternativa

---

## 4. Investigación: WireGuard para Android

### 4.1 Opciones disponibles

| Opción | Tecnología | Root | VpnService | Estado |
|--------|-----------|------|------------|--------|
| `com.wireguard.android:tunnel` (.aar) | Go backend | No | Sí | ✅ Oficial, recomendado |
| `wireguard-go` (compilado) | Go | No | Sí | ⚠️ Requiere NDK para compilar |
| `boringtun` (Cloudflare) | Rust | No | Sí | ⚠️ Experimental |
| WireGuard Kotlin puro | Kotlin | No | Sí | ❌ No existe |

### 4.2 .aar oficial (recomendado)

```gradle
implementation("com.wireguard.android:tunnel:1.0.20230706")
```

**Cómo funciona**:
1. `GoBackend` embebe el runtime de Go con WireGuard
2. `VpnService.establishTunInterface()` crea la interfaz TUN
3. `GoBackend.setState(tunnel, State.UP, config)` activa el túnel
4. WireGuard endpoint = `127.0.0.1:51820` (wstunnel local)

**Ya implementado** en nuestro código (`WireGuardManager.kt`, `VpnGatewayService.kt`).

### 4.3 WireGuard sobre WebSocket

**Flujo de datos**:
```
App → TUN interface → WireGuard (GoBackend)
  → UDP packet to 127.0.0.1:51820
  → wstunnel client (forwards UDP over WebSocket)
  → wstunnel server (extracts UDP)
  → WireGuard server (processes packet)
  → Internet
```

**MTU considerations**:
- WireGuard overhead: ~60 bytes
- WebSocket overhead: ~2-14 bytes (frame header)
- SSTP overhead: ~8 bytes (header)
- **MTU recomendado**: 1300 (ya configurado en nuestro código)

### 4.4 Expo RN + WireGuard

**No existe** un paquete npm/expo para WireGuard. La opción es crear un Expo Module Kotlin que wrappee GoBackend.

---

## 5. Investigación: Expo React Native + Módulos Nativos

### 5.1 Expo Modules API (2025-2026)

La API moderna de Expo para módulos nativos soporta Kotlin directamente:

```kotlin
// modules/vpn-module/src/main/java/com/ucfvpn/VpnModule.kt
class VpnModule : Module() {
    override fun definition() = ModuleDefinition {
        Name("UcfVpn")

        // Funciones sincrónicas
        Function("getStatus") {
            return@Function vpnOrchestrator.getCurrentStatus()
        }

        // Funciones asíncronas (coroutines)
        AsyncFunction("connect") Coroutine { config: VpnConfig ->
            vpnOrchestrator.start(config)
        }

        AsyncFunction("disconnect") Coroutine {
            vpnOrchestrator.stop()
        }

        // Eventos → JS
        Events("onStateChanged", "onLog")

        // Lifecycle
        OnCreate {
            // Inicializar módulos VPN
        }

        OnDestroy {
            // Cleanup
        }
    }
}
```

### 5.2 Estructura del proyecto Expo

```
ucf-vpn/
├── app/                          # Expo Router (páginas)
│   ├── (tabs)/
│   │   ├── index.tsx             # Pantalla de conexión
│   │   ├── config.tsx            # Configuración
│   │   └── logs.tsx              # Logs
│   └── _layout.tsx
├── modules/
│   └── ucf-vpn/                  # Expo Module nativo
│       ├── expo-module.config.json
│       └── android/
│           └── src/main/java/com/ucfvpn/
│               ├── VpnModule.kt          # Expo Module entry
│               ├── sstp/                 # SSTP (migrado del código actual)
│               ├── proxy/                # Proxy Auth (migrado)
│               ├── wstunnel/             # wstunnel manager (migrado)
│               ├── vpn/                  # VpnService + WireGuard (migrado)
│               └── orchestrator/         # Orquestador (migrado)
├── app.json                      # Expo config
└── package.json
```

### 5.3 AndroidManifest.xml (via Expo Config Plugin)

```xml
<!-- Auto-generado por Expo config plugin -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<service
    android:name=".vpn.UcfVpnService"
    android:permission="android.permission.BIND_VPN_SERVICE"
    android:exported="false">
    <intent-filter>
        <action android:name="android.net.VpnService" />
    </intent-filter>
</service>
```

### 5.4 Bridge JS → Native

```typescript
// modules/ucf-vpn/index.ts
import UcfVpn from './src/UcfVpnModule';

export type VpnStatus = 'disconnected' | 'connecting' | 'connected' | 'error';

export function connect(config: {
  sstpHost: string;
  sstpPort: number;
  sstpUsername: string;
  sstpPassword: string;
  proxyHost: string;
  proxyPort: number;
  proxyUsername: string;
  proxyPassword: string;
  wstunnelUrl: string;
  wireGuardEndpoint: string;
  wireGuardPublicKey: string;
}): Promise<void> {
  return UcfVpn.connect(config);
}

export function disconnect(): Promise<void> {
  return UcfVpn.disconnect();
}

export function getStatus(): VpnStatus {
  return UcfVpn.getStatus();
}

// Eventos
export const onStateChanged = UcfVpn.addListeners?.('onStateChanged');
export const onLog = UcfVpn.addListeners?.('onLog');
```

---

## 6. Análisis de Viabilidad

### 6.1 ¿Qué se puede migrar a JS/TS?

| Componente | Migrable a JS? | Razón |
|-----------|----------------|-------|
| UI (pantallas, navegación) | ✅ SÍ | React Native está para esto |
| Configuración (forms, validación) | ✅ SÍ | Lógica pura de UI |
| Logging display | ✅ SÍ | Solo mostrar strings |
| Estado de conexión (display) | ✅ SÍ | Solo observar eventos |
| SSTP client | ❌ NO | Requiere SSL socket nativo |
| PPP/HDLC | ❌ NO | Requiere manipulación de bytes a bajo nivel |
| Proxy Auth (HTTP) | ⚠️ PARCIAL | OkHttp nativo o fetch() de RN |
| wstunnel process | ❌ NO | Requiere ProcessBuilder nativo |
| WireGuard GoBackend | ❌ NO | Requiere .aar + VpnService |
| VpnService | ❌ NO | Android API nativa |
| Keystore (claves) | ❌ NO | Android Keystore API nativa |
| protect() (anti-loop) | ❌ NO | VpnService API nativa |

### 6.2 Complejidad de migración

| Componente | Complejidad | Esfuerzo estimado |
|-----------|-------------|-------------------|
| Scaffold Expo + Router | Baja | 1-2 días |
| Expo Module skeleton | Media | 1 día |
| Migrar SSTP → Expo Module | Alta | 3-5 días |
| Migrar wstunnel → Expo Module | Media | 1-2 días |
| Migrar WireGuard → Expo Module | Media | 1-2 días |
| Migrar Proxy Auth | Baja | 1 día |
| Migrar Orquestador | Media | 2-3 días |
| UI en React Native | Media | 3-5 días |
| Testing end-to-end | Alta | 3-5 días |
| **Total estimado** | | **15-25 días** |

### 6.3 Riesgos

1. **Expo Go vs Development Build**: VPN requiere módulos nativos, así que NO se puede usar Expo Go. Necesita **Expo Dev Client** o **Development Build**.

2. **Background execution**: Las apps Expo en iOS tienen limitaciones estrictas de background. En Android es más flexible pero requiere Foreground Service.

3. **Performance del bridge**: El bridge JS↔Native tiene overhead. Para VPN (high-throughput), esto puede ser un problema. Solución: toda la lógica de red permanece en Kotlin, JS solo controla.

4. **Debugging**: Depurar código nativo dentro de Expo requiere Android Studio + Flipper o similar.

---

## 7. Recomendación de Arquitectura

### Arquitectura híbrida: Expo RN (UI) + Kotlin Expo Modules (VPN)

```
┌─────────────────────────────────────────────┐
│           React Native (TypeScript)          │
│  ┌─────────┐  ┌──────────┐  ┌──────────┐   │
│  │ Status  │  │  Config  │  │   Logs   │   │
│  │ Screen  │  │  Screen  │  │  Screen  │   │
│  └────┬────┘  └────┬─────┘  └────┬─────┘   │
│       │            │             │          │
│       └────────────┼─────────────┘          │
│                    │                        │
│         ┌──────────▼──────────┐             │
│         │   UcfVpnModule      │             │
│         │  (Expo Module API)  │             │
│         └──────────┬──────────┘             │
│                    │ JSI Bridge             │
├────────────────────┼────────────────────────┤
│           Kotlin Native Module              │
│  ┌─────────────────▼───────────────────┐    │
│  │         VpnOrchestrator              │    │
│  │    (misma lógica, sin cambios)       │    │
│  └──┬──────────┬──────────┬───────────┘    │
│     │          │          │                 │
│  ┌──▼──┐  ┌───▼───┐  ┌───▼────┐  ┌──────┐│
│  │SSTP │  │Proxy  │  │wstunnel│  │WireG.││
│  │Kotlin│  │Auth   │  │Binary │  │GoBknd││
│  └──┬──┘  └───────┘  └───┬────┘  └──┬───┘│
│     │                    │          │      │
│  ┌──▼────────────────────▼──────────▼───┐  │
│  │        VpnService (Android)          │  │
│  │   TUN interface + protect()          │  │
│  └──────────────────────────────────────┘  │
└─────────────────────────────────────────────┘
```

### Flujo de datos (sin cambios):
```
App → TUN → WireGuard (127.0.0.1:51820) → wstunnel → SSTP → Red física
```

### Cambios necesarios:

1. **Eliminar**: `ui/` completo (Jetpack Compose) → Reemplazado por React Native
2. **Eliminar**: `VpnViewModel.kt` → Reemplazado por Expo Module bridge
3. **Mantener TODO lo nativo**: sstp/, proxy/, wstunnel/, vpn/, wg/, orchestrator/, state/
4. **Agregar**: `VpnModule.kt` (Expo Module entry point)
5. **Agregar**: Expo config plugin para AndroidManifest.xml
6. **Agregar**: TypeScript types y hooks para el bridge

---

## 8. Plan de Implementación

### Fase 1: Scaffold Expo (2 días)
- [ ] `npx create-expo-app ucf-vpn-rn --template tabs`
- [ ] Configurar Expo Router con 3 pantallas
- [ ] Crear Expo Module skeleton (`modules/ucf-vpn/`)
- [ ] Configurar `expo-module.config.json`
- [ ] Verificar build con `npx expo run:android`

### Fase 2: Expo Module Core (3 días)
- [ ] Migrar `VpnOrchestrator.kt` al Expo Module
- [ ] Crear `VpnModule.kt` con Functions/AsyncFunctions
- [ ] Implementar eventos `onStateChanged`, `onLog`
- [ ] Agregar Expo Config Plugin para AndroidManifest
- [ ] Probar bridge JS → Native básico

### Fase 3: Migrar componentes nativos (5 días)
- [ ] Migrar SSTP (`sstp/`) al módulo
- [ ] Migrar Proxy Auth (`proxy/`) al módulo
- [ ] Migrar wstunnel (`wstunnel/`) al módulo
- [ ] Migrar WireGuard (`vpn/`, `wg/`) al módulo
- [ ] Migrar State Machine (`state/`) al módulo
- [ ] Verificar stack completo funciona via Expo Module

### Fase 4: UI React Native (5 días)
- [ ] Pantalla de conexión (botón connect/disconnect, estado)
- [ ] Pantalla de configuración (SSTP, Proxy, wstunnel, WireGuard)
- [ ] Pantalla de logs (LazyList, auto-scroll)
- [ ] Hooks personalizados (`useVpn`, `useVpnConfig`)
- [ ] Persistencia de config (AsyncStorage o SecureStore)

### Fase 5: Testing y polish (5 días)
- [ ] Tests de integración end-to-end
- [ ] Test en dispositivo real
- [ ] Optimización de performance
- [ ] Manejo de errores y edge cases
- [ ] Documentación

---

## 9. Decisiones Pendientes

| Decisión | Opción A | Opción B | Recomendación |
|----------|----------|----------|---------------|
| ¿Expo Router o React Navigation? | Expo Router (file-based) | React Navigation | Expo Router (moderno) |
| ¿wstunnel binario o Kotlin puro? | Binario embebido (actual) | WebSocket Kotlin puro | Binario (menor riesgo) |
| ¿Proxy Auth en Kotlin o JS? | Kotlin (actual) | fetch() en JS | Kotlin (control total) |
| ¿Expo Dev Client o bare workflow? | Dev Client (managed) | Bare workflow | Dev Client |
| ¿Config storage? | expo-secure-store | AsyncStorage | SecureStore (claves) |
| ¿Estado global? | Zustand | Context + useReducer | Zustand (simple) |

---

## 10. Conclusión

**La migración a Expo React Native es viable** pero la lógica VPN permanece 100% en Kotlin. Expo RN aporta:
- ✅ UI más rápida de desarrollar (hot reload, componentes)
- ✅ Mejor experiencia de desarrollo (TypeScript, ESLint, Prettier)
- ✅ Posibilidad de iOS en el futuro (con mismo código nativo)
- ✅ Comunidad más grande de UI components
- ✅ Expo Dev Client para debug nativo

**El costo** es:
- ⚠️ Curva de aprendizaje Expo Modules API
- ⚠️ Build más lento (native compilation)
- ⚠️ No se puede usar Expo Go (necesita Dev Client)
- ⚠️ Overhead del bridge JS↔Native (mínimo para VPN)
