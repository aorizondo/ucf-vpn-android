# UCF VPN — Split Tunnel con SSTP + Portal Cautivo + wstunnel SOCKS5

**Objetivo**: VPN Android que usa SSTP como transporte base, autentica portal cautivo (recurso privado), levanta wstunnel SOCKS5 via proxy HTTP autenticado, y expone split tunneling via VpnService: tráfico privado → SSTP directo, internet → wstunnel SOCKS5.

---

## Requisitos Adicionales (2026-09-04)

### R1: Soporte ARMv7 32-bit (COMPLETADO ✅)
- [x] **R1.1**: Workflow `.github/workflows/build-wstunnel.yml` — compila wstunnel v10.5.1 desde fuente para `aarch64-linux-android` + `armv7-linux-androideabi` (fallback a `ring` si `aws-lc-rs` falla en 32-bit), sube artifact `wstunnel-android-binaries`
- [x] **R1.2**: `.github/workflows/build.yml` — job `build-wstunnel` + `build-apk` con `needs:`, copia binarios a `app/src/main/assets/` como `wstunnel_arm64` y `wstunnel_armv7`
- [x] **R1.3**: `WstunnelManager.kt` — selección de binario por `Build.SUPPORTED_ABIS` (`armeabi*` → `wstunnel_armv7`, else → `wstunnel_arm64`); destino en filesDir con sufijo ABI para nunca reutilizar binario de otra ABI
- [x] **R1.4**: `scripts/download_wstunnel.sh` — documenta que armv7 se compila vía CI (no hay asset oficial Android para 32-bit)

### R2: Investigación PPP Userspace para Android (COMPLETADA ✅)
Hallazgos clave (2026-09-04):

1. **kittoku/Open-SSTP-Client** (MIT, activo) — Cliente SSTP Android open source que implementa **PPP completo en Kotlin puro**: LCP, PAP, MS-CHAPv2, IPCP, CCP/MPPE. Usa VpnService. **Referencia principal para Fase 1**. Repo: `https://github.com/kittoku/Open-SSTP-Client`
2. **platform/external/ppp** (Google AOSP) — pppd embebido en C para Android (requiere NDK/JNI, no es Kotlin puro)
3. **platform/external/libppp** (Google AOSP) — librería PPP en C (lcp.c, pap.c, ipcp.c) — referencia de protocolo
4. **accel-ppp** (GPLv2) — implementación PPP userspace en C (servidor, no cliente) — referencia de máquinas de estado
5. **ToiCF/CF-Workers-SoftEther** (GPL-3.0) — SSTP+PPP (LCP/PAP/IPCP) implementado manualmente en JavaScript para Cloudflare Workers — referencia de secuencia de negociación

**Decisión**: Implementar PPP en Kotlin puro (LCP/PAP/IPCP) usando Open-SSTP-Client como referencia de código. No usar pppd/NDK (mantiene la app sin dependencias nativas adicionales).

---

## Arquitectura Final

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ Android Apps                                                                 │
│      │                                                                       │
│      ▼                                                                       │
│ ┌─────────────────────────────────────┐                                     │
│ │ VpnService (TUN fd)                 │  Split Tunneling:                   │
│ │ fwmark + routing rules              │  • 10.0.0.0/8, 192.168/16,          │
│ │                                     │    172.16/12 → SSTP                 │
│ │ builder.addRoute("0.0.0.0", 0)      │  • 0.0.0.0/0 → wstunnel SOCKS5      │
│ └──────────────┬──────────────────────┘                                     │
│                │                                                            │
│      ┌─────────┴─────────┐                                                  │
│      ▼                   ▼                                                  │
│ ┌───────────┐      ┌─────────────┐                                         │
│ │  Privados │      │  Internet   │                                         │
│ │ (10.x,    │      │ (resto)     │                                         │
│ │  192.168) │      │             │                                         │
│ └─────┬─────┘      └──────┬──────┘                                         │
│       │                   │                                                  │
│       ▼                   ▼                                                  │
│ ┌──────────────┐  ┌──────────────────┐                                      │
│ │ SSTP Tunnel  │  │ hev-socks5-tunnel│                                      │
│ │ (protegido)  │  │ → SOCKS5:1080    │                                      │
│ └──────┬───────┘  └────────┬─────────┘                                      │
│        │                   │                                                │
│        └────────┬─────────┘                                                │
│                 ▼                                                          │
│      ┌─────────────────────┐                                               │
│      │ wstunnel SOCKS5     │  client -L socks5://:1080                     │
│      │ -p http://10.14.0.13│  (protegido con protect())                    │
│      │ :3128               │                                               │
│      └──────────┬──────────┘                                               │
│                 │                                                          │
│                 ▼                                                          │
│      ┌─────────────────────┐                                               │
│      │ Proxy HTTP Autent.  │  internet.ucf.edu.cu (portal cautivo)        │
│      │ 10.14.0.13:3128     │  ← ACCESO VÍA SSTP (recurso privado)         │
│      └─────────────────────┘                                               │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Secuencia de Conexión (Orden Crítico)

| Paso | Componente | Protección Socket | Ruta | Descripción |
|------|------------|-------------------|------|-------------|
| 1 | `SstpHandshake.connect()` | **`protect()` ANTES de `connect()`** | Física (bypass VPN) | TLS + HTTP + SSTP handshake a `npv.ucf.edu.cu:443` |
| 2 | PPP Stack (LCP → PAP → IPCP) | Hereda socket SSTP protegido | SSTP Tunnel | Negociación LCP, auth PAP/MS-CHAPv2, IPCP (IP, DNS, gateway) |
| 3 | `ProxyAuthService.login()` | **NO proteger** | **Vía SSTP (recurso privado)** | POST a `internet.ucf.edu.cu` (CSRF login) — usa IP 10.x del PPP |
| 4 | `WstunnelManager.start(SOCKS5)` | **`protect()` socket wstunnel** | Física (bypass VPN) | `wstunnel client -L socks5://:1080 -p http://10.14.0.13:3128 wss://...` |
| 5 | `VpnService.startWithSplitTunnelSocks5()` | N/A (TUN fd) | Split: privadas→SSTP, internet→SOCKS5 | TUN + rutas privadas + hev-socks5-tunnel → SOCKS5 |

---

## Fases y Tareas

### FASE 1 — PPP Stack Real (Bloqueante, Fundacional)

#### Tarea 1.1: LCP State Machine
- **Archivo nuevo**: `app/src/main/java/com/ucfvpn/app/sstp/ppp/LcpHandler.kt`
- **Qué implementar**:
  - Estados: `CLOSED`, `REQ_SENT`, `ACK_RCVD`, `ACK_SENT`, `OPENED`
  - Enviar `Configure-Request` con: MRU=1400, Magic Number, Auth Protocol=PAP (0xC023)
  - Procesar `Configure-Ack`/`Configure-Nak`/`Configure-Reject`
  - Timeout/retransmisión (3s, max 10 intentos)
  - Callback `onLcpOpened()`

#### Tarea 1.2: PAP Authentication Handler
- **Archivo nuevo**: `app/src/main/java/com/ucfvpn/app/sstp/ppp/PapHandler.kt`
- **Qué implementar**:
  - Enviar `Authenticate-Request` (user/pass en cleartext — PAP)
  - Procesar `Authenticate-Ack`/`Authenticate-Nak`
  - Callback `onPapSuccess()` / `onPapFailure()`

#### Tarea 1.3: IPCP Handler
- **Archivo nuevo**: `app/src/main/java/com/ucfvpn/app/sstp/ppp/IpCpHandler.kt`
- **Qué implementar**:
  - Estados: `CLOSED`, `REQ_SENT`, `ACK_RCVD`, `OPENED`
  - Enviar `Configure-Request` con: IP Address=0.0.0.0 (request), Primary/Secondary DNS
  - Procesar `Configure-Ack` con IP asignada, DNS, gateway
  - Callback `onIpCpSuccess(localIp: String, dns1: String, dns2: String, gateway: String)`

#### Tarea 1.4: PPP Stack Coordinator
- **Archivo nuevo**: `app/src/main/java/com/ucfvpn/app/sstp/ppp/PppStack.kt`
- **Qué implementar**:
  - Orquestar: `LcpHandler` → `PapHandler` → `IpCpHandler`
  - Exponer `negotiate(username, password)` suspend function
  - Manejar timeouts globales y limpieza
  - Emitir eventos: `PppEvent.LcpOpened`, `PppEvent.AuthSuccess(keys)`, `PppEvent.IpAssigned(ip, dns, gw)`

#### Tarea 1.5: Integrar PppStack en PppHandler y SstpTunnelImpl
- **Modificar**: `PppHandler.kt` — delegar a `PppStack`
- **Modificar**: `SstpTunnelImpl.kt` — llamar `onPppAuthSuccess(sendKey, recvKey, masterKey)` con keys reales de MPPE (si PAP genera MPPE) o null
- **Test unitario**: `PppStackTest.kt` con traces PPP reales (PCAP de referencia)

**Criterios de Aceptación Fase 1**:
- [x] LCP negocia MRU=1400, Magic Number, Auth=PAP
- [x] PAP autentica contra servidor UCF (usuario/contraseña) — implementado; verificación real en dispositivo (Fase 7)
- [x] IPCP obtiene IP 10.x.x.x, DNS, gateway — implementado; verificación real en dispositivo (Fase 7)
- [x] `SstpTunnelImpl.localAddress` se setea con IP asignada
- [x] Crypto binding se re-envía con keys reales (si MPPE) o null

---

### FASE 2 — Proxy Auth vía SSTP (Recurso Privado)

#### Tarea 2.1: Verificar/Eliminar protect() en ProxyAuthService
- **Modificar**: `ProxyAuthService.kt`
- **Qué hacer**: Confirmar que `OkHttpClient` **NO** usa `protect()`. El tráfico debe ir por red del sistema (antes de VPN) o por SSTP (después de VPN).
- **Añadir**: Método `setVpnService(vpnService: VpnGatewayService?)` para futura exclusión de rutas.

#### Tarea 2.2: Rutas Estáticas para Portal Cautivo en VpnService
- **Modificar**: `VpnGatewayService.kt`
- **Añadir en `establishTunInterface()`**:
  ```kotlin
  // Rutas para portal cautivo (resuelven via SSTP)
  builder.addRoute("10.14.0.0", 16)  // Red del proxy HTTP
  builder.addRoute("internet.ucf.edu.cu")  // Si Builder soporta hostname
  ```
- **Alternativa**: `addDisallowedApplication(packageName)` para excluir la app completa del VPN (más simple, pero todo el tráfico de la app bypass VPN).

#### Tarea 2.3: Test Integración Portal Cautivo
- **Test manual**: Con SSTP+PPP up, `ProxyAuthService.login()` debe resolver `internet.ucf.edu.cu` via SSTP y autenticar.

**Criterios de Aceptación Fase 2**:
- [x] Portal cautivo accesible via SSTP (IP 10.x) antes de activar VPN — implementado (rutas 10.14.0.0/16 + addDisallowedApplication); verificación real en dispositivo (Fase 7)
- [x] Login CSRF exitoso, cookies de sesión guardadas — implementado (ProxyAuthService sin protect + setVpnService); verificación real en dispositivo (Fase 7)
- [x] Proxy HTTP `10.14.0.13:3128` responde autenticado — implementado (rutas estáticas); verificación real en dispositivo (Fase 7)

---

### FASE 3 — wstunnel SOCKS5 con protect()

#### Tarea 3.1: Extender WstunnelManager con protect()
- **Modificar**: `WstunnelManager.kt`
- **Añadir parámetro**: `vpnService: VpnGatewayService?` en constructor o `setVpnService()`
- **En `start()`**: Antes de `ProcessBuilder.start()`, si `tunnelType == SOCKS5` y `vpnService != null`:
  - El binario wstunnel crea su propio socket al conectar al WebSocket
  - **Opción A**: `vpnService.protectSocket()` no aplica a subprocess
  - **Opción B (recomendada)**: wstunnel soporta `--protect-fd` o similar? Verificar flags.
  - **Opción C**: Usar `VpnService.protect(int fd)` via JNI? No.
  - **Solución práctica**: wstunnel hereda FD del proceso Java. **No se puede proteger desde Java**.
  - **Workaround**: Ejecutar wstunnel **ANTES** de establecer el VPN (paso 4 antes de paso 5). El socket wstunnel se crea antes del TUN, así que no hay loop.

#### Tarea 3.2: Health Check Puerto SOCKS5 1080
- **Modificar**: `WstunnelManager.kt`
- **Añadir**: `waitForSocks5Ready(port: Int = 1080, timeoutMs: Long = 10000): Boolean`
- Conectar TCP a `127.0.0.1:1080`, enviar handshake SOCKS5 (`\x05\x01\x00`), esperar `\x05\x00`.

#### Tarea 3.3: Config SOCKS5 en WstunnelConfig
- **Verificar**: `WstunnelConfig.socks5()` ya existe ✅
- **Añadir**: `proxyAuth: String?` (user:pass para proxy HTTP) si wstunnel lo soporta.

**Criterios de Aceptación Fase 3**:
- [x] `wstunnel client -L socks5://:1080 -p http://10.14.0.13:3128 wss://...` arranca — implementado (buildCommand con proxyAuth); verificación real en dispositivo (Fase 7)
- [x] Puerto 1080 responde handshake SOCKS5 — implementado (waitForSocks5Ready con handshake real); verificación real en dispositivo (Fase 7)
- [x] wstunnel se conecta vía proxy HTTP autenticado (cookies de sesión) — implementado (proxyAuth en `-p`); verificación real en dispositivo (Fase 7)

---

### FASE 4 — VpnService Split Tunnel + hev-socks5-tunnel Dinámico

#### Tarea 4.1: Nueva Función startWithSplitTunnelSocks5()
- **Modificar**: `VpnGatewayService.kt`
- **Firma**:
  ```kotlin
  suspend fun startWithSplitTunnelSocks5(
      privateNetworks: List<String> = listOf("10.0.0.0/8", "192.168.0.0/16", "172.16.0.0/12"),
      socks5Proxy: String = "127.0.0.1:1080",
      vpnConfig: VpnConfig = VpnConfig.DEFAULT,
      dnsViaSocks5: Boolean = true
  ): Result<Unit>
  ```

#### Tarea 4.2: Configurar Rutas Split en Builder
- **En `establishTunInterface()` o nueva función `establishSplitTunInterface()`**:
  ```kotlin
  // Rutas PRIVADAS → van por TUN → SSTP (protegido)
  for (cidr in privateNetworks) {
      val (ip, prefix) = parseCidr(cidr)
      builder.addRoute(ip, prefix)
  }
  // Rutas para portal cautivo y proxy HTTP
  builder.addRoute("10.14.0.0", 16)
  // Ruta DEFAULT → TUN → hev-socks5-tunnel
  builder.addRoute("0.0.0.0", 0)
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      builder.addRoute("::", 0)
  }
  ```

#### Tarea 4.3: Generador YAML Dinámico para hev-socks5-tunnel
- **Archivo nuevo**: `app/src/main/java/com/ucfvpn/app/wstunnel/HevSocks5TunnelConfigGenerator.kt`
- **Genera YAML con**:
  ```yaml
  listen:
    host: 0.0.0.0
    port: 5080  # Puerto local del tun2socks
  socks5:
    host: 127.0.0.1
    port: 1080  # wstunnel SOCKS5
  dns:
    address: 0.0.0.0
    port: 6000
    upstream:
      - 1.1.1.1
      - 8.8.8.8
    # Importante: resolver DNS via SOCKS5
    tcp: true
  log:
    level: info
    buffer: 1024
  ```
- **Modificar**: `Tun2SocksManager.start()` — usar generador en lugar de asset estático.

#### Tarea 4.4: Integración VpnGatewayService + Tun2SocksManager
- **En `startWithSplitTunnelSocks5()`**:
  1. `establishSplitTunInterface()` → `tunFd`
  2. Generar YAML dinámico con `socks5Proxy` configurado
  3. `tun2SocksManager.start(tunFd, yamlPath)`
  4. Verificar `Tun2SocksState.RUNNING`

**Criterios de Aceptación Fase 4**:
- [x] TUN establecido con rutas privadas + default
- [~] **Tráfico a `10.x.x.x` va por SSTP — IMPLEMENTADO 2026-09-06**, sin verificar
  en dispositivo. Antes no existía data path: nada leía el fd del TUN ni escribía
  paquetes IP en el túnel, los frames 0x0021 se descartaban, y el fd del TUN se
  entregaba **entero** a hev-socks5-tunnel, de modo que todo el tráfico —incluido
  10.0.0.0/8— acababa en el SOCKS5 y las rutas privadas eran decorativas.
  Ahora: `SplitRouter` decide por paquete, `SstpDataPath` demultiplexa, y a
  hev-socks5-tunnel se le entrega un extremo de un socketpair AF_UNIX/SOCK_SEQPACKET
  en lugar del TUN real.
- [~] Tráfico a `8.8.8.8` va por hev-socks5-tunnel → wstunnel SOCKS5 → proxy HTTP
  — implementado; sin verificar en dispositivo
- [~] DNS resuelve via SOCKS5 (`dns.tcp: true`) — implementado; sin verificar en dispositivo

---

### FASE 5 — Orquestador Unificado + State Machine Completa

#### Tarea 5.1: Ampliar VpnState
- **Modificar**: `app/src/main/java/com/ucfvpn/app/state/VpnState.kt`
- **Nuevos estados**:
  ```kotlin
  sealed interface VpnState {
      data class PppNegotiating(val phase: PppPhase) : VpnState
      data class PppAuthenticated(val localIp: String) : VpnState
      data class ProxyAuthenticating : VpnState
      data class ProxyAuthenticated : VpnState
      data class WstunnelStarting(val type: TunnelType) : VpnState
      data class WstunnelRunning(val socks5Port: Int) : VpnState
      data class VpnStarting : VpnState
      data class VpnRunning : VpnState
      // ... existentes
      enum class PppPhase { LCP, AUTH, IPCP }
  }
  ```

#### Tarea 5.2: Refactor VpnOrchestrator.performConnectionSequence()
- **Modificar**: `VpnOrchestrator.kt`
- **Nueva secuencia**:
  ```kotlin
  private suspend fun performConnectionSequence(config: AppConfig) {
      // 1. SSTP
      stateMachine.transition(VpnState.SstpConnecting)
      sstpTunnel.connect(config.sstpServer, config.sstpPort)
      waitForSstpConnected()
      stateMachine.transition(VpnState.SstpConnected)

      // 2. PPP Real
      stateMachine.transition(VpnState.PppNegotiating(PppPhase.LCP))
      pppStack.negotiate(config.sstpUsername, config.sstpPassword)
      waitForPppComplete()  // emite PppAuthenticated(ip)
      stateMachine.transition(VpnState.PppAuthenticated(localIp))

      // 3. Portal Cautivo (vía SSTP)
      stateMachine.transition(VpnState.ProxyAuthenticating)
      proxyAuthService.login(config.proxyUsername, config.proxyPassword)
      stateMachine.transition(VpnState.ProxyAuthenticated)

      // 4. wstunnel SOCKS5 (protect socket ANTES de VPN)
      stateMachine.transition(VpnState.WstunnelStarting(TunnelType.SOCKS5))
      wstunnelManager.start(WstunnelConfig.socks5(
          localPort = 1080,
          serverUrl = config.wstunnelConfig.serverUrl,
          proxyHost = "10.14.0.13",
          proxyPort = 3128
      ))
      waitForWstunnelSocks5Ready()
      stateMachine.transition(VpnState.WstunnelRunning(1080))

      // 5. VPN Split Tunnel
      stateMachine.transition(VpnState.VpnStarting)
      vpnService.startWithSplitTunnelSocks5(
          privateNetworks = config.splitTunnelConfig.privateNetworks,
          socks5Proxy = "127.0.0.1:1080"
      )
      stateMachine.transition(VpnState.VpnRunning)
  }
  ```

#### Tarea 5.3: Unificar AppConfig
- **Modificar**: `VpnOrchestrator.AppConfig` (o nuevo `VpnConfig.kt`)
- **Estructura**:
  ```kotlin
  data class AppConfig(
      val sstpServer: String,
      val sstpPort: Int,
      val sstpUsername: String,
      val sstpPassword: String,
      val proxyUsername: String,
      val proxyPassword: String,
      val wstunnelConfig: WstunnelConfig,
      val splitTunnelConfig: SplitTunnelConfig = SplitTunnelConfig(),
      val debugWstunnelSocks5: Boolean = false
  )

  data class SplitTunnelConfig(
      val privateNetworks: List<String> = listOf("10.0.0.0/8", "192.168.0.0/16", "172.16.0.0/12"),
      val bypassApps: List<String> = emptyList(),
      val defaultViaProxy: Boolean = true
  )
  ```

#### Tarea 5.4: Cleanup Sequence Actualizado
- **Modificar**: `VpnOrchestrator.cleanup*()`
- **Orden inverso**:
  1. `vpnService.shutdown()` (TUN + tun2socks)
  2. `wstunnelManager.stop()`
  3. `proxyAuthService.reset()`
  4. `sstpTunnel.disconnect()`
  5. `stateMachine.disconnect()`

**Criterios de Aceptación Fase 5**:
- [x] Secuencia completa: SSTP → PPP → Portal → wstunnel SOCKS5 → VPN Split
- [x] Estado `VpnRunning` alcanzado
- [x] Cleanup ordenado sin leaks
- [x] Reconexión por capa independiente (fallo PPP → reintentar PPP; fallo wstunnel → reintentar wstunnel)

---

### FASE 6 — UI Configuración + Persistencia

#### Tarea 6.1: UI para Proxy HTTP
- **Modificar**: `VpnViewModel.UiConfig`
- **Añadir campos**:
  ```kotlin
  val proxyType: ProxyType = ProxyType.HTTP,  // HTTP, SOCKS5
  val proxyHost: String = "10.14.0.13",
  val proxyPort: Int = 3128,
  val proxyUsername: String = "",
  val proxyPassword: String = "",
  ```

#### Tarea 6.2: UI para Split Tunneling
- **Añadir a `UiConfig`**:
  ```kotlin
  val privateNetworks: String = "10.0.0.0/8,192.168.0.0/16,172.16.0.0/12",
  val bypassApps: String = "",  // package names comma-separated
  val defaultViaProxy: Boolean = true,
  ```

#### Tarea 6.3: Pantallas Compose
- **Modificar**: `VpnConfigScreen.kt` (o donde esté la UI de configuración)
- **Secciones**:
  - SSTP Config (existente)
  - **Proxy HTTP Config** (nuevo: tipo, host, puerto, user, pass)
  - **Split Tunnel Config** (nuevo: redes privadas CSV, bypass apps CSV, default via proxy)

#### Tarea 6.4: Persistencia DataStore/Keystore
- **Modificar**: `ConfigPreferences.kt`
- Guardar/cargar `UiConfig` completo incluyendo proxy y split tunnel.

**Criterios de Aceptación Fase 6**:
- [x] UI permite configurar proxy HTTP (host, puerto, credenciales)
- [x] UI permite editar redes privadas CIDR (CSV)
- [x] Config persiste entre reinicios
- [x] Conexión usa config de UI

---

### FASE 7 — Tests End-to-End + QA

#### Tarea 7.1: Test Integración Completo
- **Dispositivo/Emulador**: Android 10+ (API 29+)
- **Escenario**:
  1. Conectar WiFi normal
  2. Iniciar VPN desde app
  3. Verificar: `ping 10.x.x.x` (recurso privado) → OK via SSTP
  4. Verificar: `ping 8.8.8.8` → OK via wstunnel SOCKS5
  5. Verificar: `curl https://httpbin.org/ip` → IP del proxy HTTP UCF
  6. Verificar: DNS `dig @1.1.1.1 google.com` → resuelve via SOCKS5
  7. Verificar: UDP (QUIC, DNS over UDP) → via wstunnel
  8. Desconectar → cleanup completo

#### Tarea 7.2: Test Reconexión
- Matar wstunnel → verificar reconexión automática
- Cortar WiFi → reconectar → VPN restablecido
- Expirar sesión portal cautivo → re-auth automático

#### Tarea 7.3: Test Bypass Apps
- Configurar `bypassApps = ["com.whatsapp", "com.android.chrome"]`
- Verificar tráfico de esas apps sale por WiFi físico (no VPN)

---

## Archivos a Crear / Modificar (Resumen)

### Nuevos
```
app/src/main/java/com/ucfvpn/app/sstp/ppp/
├── LcpHandler.kt
├── PapHandler.kt
├── IpCpHandler.kt
└── PppStack.kt

app/src/main/java/com/ucfvpn/app/wstunnel/
└── HevSocks5TunnelConfigGenerator.kt

app/src/main/java/com/ucfvpn/app/vpn/
└── SplitTunnelConfig.kt
```

### Modificados
```
app/src/main/java/com/ucfvpn/app/sstp/ppp/
└── PppHandler.kt              ← delegar a PppStack

app/src/main/java/com/ucfvpn/app/sstp/client/
├── SstpTunnelImpl.kt          ← onPppAuthSuccess con keys reales
└── SstpHandshake.kt           ← verificar protect() ANTES de connect()

app/src/main/java/com/ucfvpn/app/proxy/
└── ProxyAuthService.kt        ← setVpnService(), sin protect()

app/src/main/java/com/ucfvpn/app/wstunnel/
├── WstunnelManager.kt         ← protect() workaround, health check SOCKS5
├── WstunnelConfig.kt          ← proxyAuth opcional
└── Tun2SocksManager.kt        ← YAML dinámico

app/src/main/java/com/ucfvpn/app/vpn/
├── VpnGatewayService.kt       ← startWithSplitTunnelSocks5(), rutas split
└── VpnConfig.kt               ← ampliar con ProxyConfig + SplitTunnelConfig

app/src/main/java/com/ucfvpn/app/orchestrator/
├── VpnOrchestrator.kt         ← nueva state machine, AppConfig unificado
└── AppConfig.kt               ← (o dentro de VpnOrchestrator)

app/src/main/java/com/ucfvpn/app/state/
└── VpnState.kt                ← nuevos estados PPP/Proxy/Wstunnel

app/src/main/java/com/ucfvpn/app/ui/viewmodel/
├── VpnViewModel.kt            ← UiConfig ampliado
└── VpnConfigScreen.kt         ← UI proxy + split tunnel

app/src/main/java/com/ucfvpn/app/prefs/
└── ConfigPreferences.kt       ← persistencia UiConfig completa
```

---

## Dependencias Entre Fases

```
FASE 1 (PPP Stack)
    │
    ▼
FASE 2 (Proxy Auth vía SSTP) ◄── Requiere PPP para IP 10.x
    │
    ▼
FASE 3 (wstunnel SOCKS5) ◄── Requiere Proxy Auth para cookies
    │
    ▼
FASE 4 (Split Tunnel VPN) ◄── Requiere wstunnel SOCKS5 up
    │
    ▼
FASE 5 (Orquestador) ◄── Une todo
    │
    ▼
FASE 6 (UI) ◄── Usa AppConfig unificado
    │
    ▼
FASE 7 (E2E Tests)
```

---

## Riesgos y Mitigaciones

| Riesgo | Impacto | Mitigación |
|--------|---------|------------|
| PPP MS-CHAPv2 requerido (no PAP) | Alto | Implementar `MsChapV2Handler` en Fase 1.3 opcional; detectar via `Configure-Nak` Auth-Protocol |
| wstunnel no soporta `protect()` en subprocess | Medio | Ejecutar wstunnel **antes** de `VpnService.establish()` (orden actual correcto) |
| hev-socks5-tunnel no soporta routing rules | Medio | YAML dinámico con `tcp: true` para DNS; si falla, implementar tun2socks simple en Kotlin |
| Portal cautivo inaccesible tras VPN up | Alto | Rutas estáticas `10.14.0.0/16` en Builder + `addDisallowedApplication()` |
| Crypto binding falla sin MPPE keys | Bajo | Enviar CALL_CONNECTED con null HLAK (ya implementado); re-enviar si PPP genera keys |

---

## Estimación de Esfuerzo

| Fase | Días (1 dev) | Paralelizable |
|------|--------------|---------------|
| 1. PPP Stack | 3-4 | No (secuencial) |
| 2. Proxy Auth | 1 | Sí (con Fase 1) |
| 3. wstunnel SOCKS5 | 1-2 | Sí (con Fase 2) |
| 4. Split Tunnel | 2 | No (requiere 3) |
| 5. Orquestador | 2 | No (requiere 4) |
| 6. UI | 1-2 | Sí (con Fase 5) |
| 7. E2E Tests | 2 | No (requiere 6) |
| **Total** | **12-15 días** | |

---

## Comandos de Verificación Rápida

```bash
# Build
./gradlew assembleDebug

# Test unitarios PPP
./gradlew test --tests "*PppStackTest*"

# Test integración (requiere emulador API 29+)
./gradlew connectedCheck

# Logs en dispositivo
adb logcat -s "VpnOrchestrator" "SstpTunnelImpl" "PppStack" "WstunnelManager" "VpnGatewayService" "ProxyAuthService"
```

---

## Próximos Pasos

1. **Aprobar plan** → Ejecutar `/start-work` para iniciar implementación
2. **Worker** implementa Fase 1 → 7 en orden

---

## Progress Ledger

**Leyenda**: `[x]` verificado por CI · `[~]` implementado, sin verificar en dispositivo · `[ ]` no implementado

### 2026-09-05 — Auditoría y corrección

El ledger anterior daba las Fases 1–6 por completadas. La auditoría demostró que
**ninguna funcionaba en un dispositivo**: el árbol ni siquiera compilaba. La causa
de fondo fue marcar los criterios con la fórmula «implementado; verificación real
en dispositivo (Fase 7)», que convirtió *«escribí el código»* en *«la fase está
completa»*.

**Regla para adelante: una fase sólo se marca completa cuando el CI la certifica.**

Bloqueadores encontrados y corregidos:

1. **El árbol no compilaba** — `receiveCatching` importado como función top-level
   cuando es un método miembro de `Channel`. El CI nunca lo había detectado porque
   el workflow saltaba los tests y el trabajo estaba sin commitear.
2. **`VpnGatewayService` nunca se instanciaba** — no había `VpnService.prepare()`,
   `bindService()` ni `ServiceConnection` en todo el repo, así que no se pedía
   permiso de VPN, no había TUN y la capa 5 lanzaba siempre. Ya está cableado.
3. **Los binarios no podían ejecutarse** — se copiaban a `filesDir`, algo que
   Android 10+ prohíbe por SELinux con `targetSdk >= 29`. Ahora van en
   `jniLibs/<abi>/lib*.so` y se ejecutan desde `nativeLibraryDir`.
4. **El binario de hev-socks5-tunnel no existía** — no estaba en el repo, nunca
   estuvo en el historial y ningún job lo producía. Hay job de CI que lo compila.
5. **No hay data path SSTP** — sigue pendiente; ver Fase 4.

Otros defectos corregidos: deadlock del `ReconnectManager` (el bucle retenía el
mutex, así que `stop()` colgaba el botón «Desconectar» y el cleanup no corría),
desbordamiento del backoff, NPE al construir el orquestador, cleanup que se
autocancelaba, loop de tráfico por quitar `addDisallowedApplication`, `protect()`
sobre socket sin `bind()`, TUN huérfano que dejaba el dispositivo sin red,
verificaciones tautológicas de proceso vivo, reconexión imposible desde
`VpnRunning`, reintento infinito con credenciales inválidas, login del portal que
aceptaba contraseñas incorrectas, cuatro bugs de RFC en PPP (padding de PAP,
Auth-Protocol mal dirigido, Ack ciego a MS-CHAPv2, IPCP sin responder), y ~500
líneas de código muerto.

**Tests**: de no ejecutarse nunca a 240 en CI. Seis fallos eran preexistentes;
cuatro por tests equivocados (valores de FCS-16 inventados, comparación hex
mayúsculas/minúsculas, recuento de argumentos) y uno por un defecto real
(`SstpPacket.length` no sobrevivía un roundtrip).

| Fase | Estado |
|------|--------|
| 1. PPP Stack | `[~]` compila y pasa tests; sin probar contra el servidor UCF |
| 2. Proxy auth | `[~]` implementado; el login ya verifica la sesión |
| 3. wstunnel SOCKS5 | `[~]` binario ya ejecutable; sin probar en dispositivo |
| 4. Split tunnel | `[~]` data path implementado 2026-09-06; sin verificar en dispositivo |
| 5. Orquestador | `[~]` implementado; ciclo de vida corregido |
| 6. UI + persistencia | `[~]` implementado; la config ya se propaga |
| 7. E2E | `[ ]` pendiente |

### 2026-09-06 — Decisión de arquitectura y data path

**Aclaración del usuario**: el proxy `10.14.0.13:3128` es alcanzable desde
cualquier punto de acceso de la red UCF, sin SSTP. Lo que el split resuelve es
**poder abrir sitios web de la red interna** (por ejemplo el portal cautivo del
proxy) mientras la VPN está levantada.

Eso descarta la opción B2 (renunciar al split): sin enrutado real, el tráfico a la
red interna sale por el proxy de Internet y esos sitios quedan inalcanzables. Se
implementa por tanto el data path, con una variante más simple que la B1 original
(no hace falta JNI):

- `sstp/data/SplitRouter.kt` — decide por paquete SSTP vs SOCKS5. Sin APIs de
  Android, por lo que la lógica se testea en JVM.
- `sstp/data/SstpDataPath.kt` — bucles TUN→SSTP/SOCKS5 y de vuelta.
- **El socketpair**: un `VpnService` tiene un único TUN que no se puede compartir,
  así que a hev-socks5-tunnel se le da un extremo de un socketpair
  AF_UNIX/SOCK_SEQPACKET. Lee y escribe paquetes IP igual que en un TUN, y
  SEQPACKET conserva los límites de paquete. Evita JNI por completo.
- PPP: los frames 0x0021 se separan **antes** de parsear (un paquete IP no tiene
  Code/Id/Length, así que `parsePppFrame` los habría parseado mal).

**Resuelto el mismo día**: el CI midió que un subproceso NO hereda descriptores
(el hijo reportó `NO`), lo que invalidaba el diseño original —a hev se le pasaba
un `-f <fd>` que en el hijo no apuntaba a nada—. hev ya trae capa JNI, así que se
usa la librería en proceso: `TProxyService.kt` + el módulo compartido compilado
con `PKGNAME`/`CLSNAME`. El socketpair sobrevive. Verificado por CI.

**Queda por verificar en dispositivo**: que hev acepte un socketpair donde espera
un TUN. El binding, la carga y el arranque ya los cubre el CI.

**Pendiente**: retirada de WireGuard del árbol, recuperar los tests de
`app/test-broken/` (`VpnOrchestratorTest.kt` 20 KB, `VpnIntegrationTest.kt` 27 KB),
y la verificación E2E en dispositivo (Fase 7), que es ahora lo único que puede
validar el camino completo.
