# Learnings — UCF VPN Android

## T1 — Android Project Scaffold (2026-07-30)

### Completado
- Repositorio git inicializado en `main`
- Root `build.gradle.kts` con AGP 8.2.2, Kotlin 1.9.22
- `app/build.gradle.kts` con minSdk=26, targetSdk=34, compileSdk=34
- Core library desugaring habilitado (WireGuard lo requiere)
- WireGuard .aar: `com.wireguard.android:tunnel:1.0.20230706`
- Dependencias: OkHttp 4.12.0, Compose BOM 2024.01.00, Coroutines 1.7.3, Timber 5.0.1
- `settings.gradle.kts` con dependencyResolutionManagement
- `gradle.properties` con AndroidX, Kotlin code style, JVM args
- `local.properties` con `sdk.dir=/mnt/opt/android-development-kit/sdk`
- `AndroidManifest.xml` con INTERNET, ACCESS_NETWORK_STATE, BIND_VPN_SERVICE
- Service: `VpnGatewayService` (VpnService subclass)
- Directorios package creados (10 packages con .gitkeep)
- `.gitignore` para Android/Kotlin/Gradle/IntelliJ
- `.github/workflows/build.yml` con 3 jobs (build+test, UI tests, APK)
- Gradle wrapper (gradlew 8.4, JDK 17)
- Placeholder `MainActivity.kt` y `VpnGatewayService.kt`
- First commit: `chore: initialize Android project with Gradle and GH Actions`


## T5 — UI Structure (2026-07-30)

### Completado
- `ConnectionState` sealed class con Disconnected/Connecting/Authenticating/Connected/Error
- `VpnViewModel` con StateFlow<ConnectionState>, log circular buffer (100 líneas), StateFlow<UiConfig>
- `ConfigScreen` con secciones colapsables (SSTP, Proxy, wstunnel, WireGuard), toggles, validación de puertos
- `StatusScreen` con indicador animado circular, estados de conexión, contador de tiempo conectado
- `LogScreen` con LazyColumn, auto-scroll, botones Clear/Copy, fuente monospace
- `Theme.kt` con Material 3 dynamic color, soporte dark/light, colores personalizados VPN
- `NavGraph.kt` con BottomNavigation de 3 tabs (Status/Config/Logs), tab seleccionado persistente
- `MainActivity.kt` actualizado con setContent usando AppNavHost y VpnViewModel via viewModels()
- `MainActivityTest.kt` con 5 tests: default state, nav switching, config rendering, log empty state, connect transition
- Añadido `activity-ktx:1.8.2` al build.gradle.kts para soporte de viewModels() delegate
- UI-only: connect()/disconnect() solo transicionan estados, sin lógica de red

## T4 — WireGuard Config Parser & Credential Storage (2026-07-30)

### Completado
- `WireGuardConfig.kt` — data class con todos los campos WG + `isValid()`, `defaultConfig`
- `WireGuardConfigRepository.kt` — interface con 9 métodos (save/load/delete config + keys + isConfigured)
- `WireGuardConfigParser.kt` — dual parser: WireGuard .aar `Config.parse()` + fallback Kotlin puro
- `WireGuardConfigRepositoryImpl.kt` — production: Android Keystore (AES-256/GCM) para privateKey/presharedKey, EncryptedSharedPreferences para config no sensible
- `WireGuardConfigRepositoryMock.kt` — in-memory para unit tests (sin Android framework)
- `WireGuardConfigRepositoryTest.kt` — 29 unit tests: data class equality, parser (7 casos), repo CRUD
- `WireGuardConfigRepositoryInstrumentedTest.kt` — 14 instrumented tests: Keystore persistence, save/load roundtrip
- Añadido `androidx.security:security-crypto:1.1.0-alpha06` al build.gradle.kts

### Decisiones de diseño
- **Defensa en profundidad**: privateKey cifrada con AES key exclusiva del Keystore (hardware-backed), blob cifrado guardado en EncryptedSharedPreferences con su propia master key. Dos capas de cifrado.
- **Parser dual**: intenta primero `com.wireguard.config.Config.parse()` del .aar, fallback a parser Kotlin si no disponible. El fallback maneja formato `Key = Value` y `Key=Value`.
- **GCM IV**: 12 bytes aleatorios, concatenados al ciphertext en Base64. Cada operación genera IV nuevo.
- **Alias Keystore**: `ucf_vpn_wg_master_key` (AES key), `ucf_vpn_wg_config` (EncryptedSharedPreferences)

### Pendiente
- `./gradlew test` no ejecutado — la descarga de Gradle 8.4 (130MB) no completa por red lenta
- Verificar compilación cuando el entorno de build esté disponible

## T7 — Proxy Authentication (2026-07-30)

### Completado
- `ProxyAuthState.kt` — enum con IDLE, AUTHENTICATING, AUTHENTICATED, EXPIRED, ERROR
- `ProxyAuthService.kt` — servicio de autenticación al portal cautivo (4-step flow)
- `ProxyAuthServiceTest.kt` — 14 unit tests con MockWebServer
- Añadido `mockwebserver3:4.12.0` como test dependency

### Flujo de login (match exacto con proxy_login.sh)
1. GET `/auth/login?next=/` → extraer CSRF token del HTML
2. POST `/auth/login?next=/` → csrfmiddlewaretoken+username+password+first_step=False
3. GET `/` → extraer nuevo CSRF token
4. POST `/` → csrfmiddlewaretoken+manual=Crear una sesion para este dispositivo

### Decisiones de diseño
- **baseUrl inyectable**: constructor acepta `baseUrl` con default `https://internet.ucf.edu.cu` para testing
- **Mutex + StateFlow**: operaciones secuenciales con Mutex, estado observable via StateFlow
- **CookieJar persistente**: `JavaNetCookieJar(CookieManager())` — cookies mantenidos entre requests
- **Detección de expiración**: GET a `/`, si redirige a `/auth/login` → sesión expirada
- **Re-autenticación**: `checkAndReauth()` detecta expiración y re-loguea automáticamente con credenciales almacenadas
- **No JSoup**: parsing HTML con regex para CSRF token
- **Corrutinas**: `Dispatchers.IO` para operaciones de red, Mutex para thread safety

### Tests implementados (14)
- Full login flow: éxito con transición a AUTHENTICATED
- Orden de requests: 4 HTTP requests en secuencia correcta
- CSRF extraction: formato estándar y con whitespace extra
- CSRF missing: falla con ERROR
- Cookie persistence: cookies enviados en requests subsecuentes
- Session expiry: detecta expiración y re-autentica
- No stored credentials: falla con ERROR
- Session valid: retorna true sin re-auth
- Server 500: falla con ERROR
- POST 403: falla con ERROR
- Empty response body: falla con ERROR
- State transitions: IDLE → AUTHENTICATING → AUTHENTICATED
- Reset: limpia estado y pierde credenciales

### Pendiente
- `./gradlew test` no ejecutado — la descarga de Gradle 8.4 (130MB) no completa por red lenta

## T6 — SSTP Client (2026-07-30)

### Completado
- `sstp/client/SstpTunnel.kt` — interface with SstpState enum, SstpTunnelCallbacks interface
- `sstp/client/SstpHandshake.kt` — SSL/TLS + HTTP + SSTP handshake, VERIFY_NONE TrustManager
- `sstp/client/SstpTunnelImpl.kt` — complete tunnel implementation with coroutines
- `sstp/client/SstpTunnelTest.kt` — 15 unit tests

### SSL/TLS Handshake
- Custom X509TrustManager with VERIFY_NONE (accepts all certificates)
- SNI hostname set to `npv.ucf.edu.cu`
- TLSv1.2/TLSv1.3 enabled protocols
- Socket timeout: 10 seconds

### HTTP SSTP_DUPLEX_POST
- Request: `SSTP_DUPLEX_POST /sra_{BA195980-CD49-458b-9E23-C84EE0ADCD75}/ HTTP/1.1`
- Headers: Host, SSTPCORRELATIONID (UUID), Content-Length: 18446744073709551615, User-Agent
- CALL_CONNECT_REQUEST sent immediately after HTTP headers
- HTTP 200 response required for success

### Crypto Binding (match Python implementation)
- MK export via reflection: `sslSession.exportKeyingMaterial("SSTP Key Binding", 32)`
- HLAK = SendKey(16) + RecvKey(16), or null HLAK if no MPPE keys
- CMK = HMAC-SHA1(MK, "SSTP inner method derived CMK\0" + HLAK)
- Certificate hash: SHA1(cert.encoded) + 12 zero bytes (padded to 32)
- Compound MAC: HMAC-SHA1(CMK, full_packet_with_zeroed_MAC)

### Keepalive
- ECHO_REQUEST sent every 30 seconds
- ECHO_RESPONSE handled when received

### State Machine
- States: DISCONNECTED → CONNECTING → CONNECTED → DISCONNECTING / ERROR
- Transition callbacks via `onStateChanged` and `SstpTunnelCallbacks`
- Automatic error handling with state transitions

### Coroutine Architecture
- Dispatchers.IO for all network operations
- receiveJob for packet receive loop
- keepaliveJob for periodic ECHO_REQUEST
- Proper cleanup on disconnect

### Unit Tests (15)
- SstpState enum values and ordinals
- SstpTunnelImpl initial state
- SSL context creation
- ECHO_REQUEST packet structure and hex
- PPP data packet wrapping
- Crypto binding attribute structure
- HMAC-SHA1 test vector
- CMK derivation
- MK export label verification
- Certificate hash padding
- CALL_CONNECT_REQUEST packet structure
- SstpPacket roundtrip tests

### Dependencies (already in build.gradle.kts)
- kotlinx-coroutines-core:1.7.3 ✅
- kotlinx-coroutines-android:1.7.3 ✅
- timber:5.0.1 ✅

### Pending
- `./gradlew test` no ejecutado — Gradle download timeout


## T8 — wstunnel Wrapper (2026-07-30)

### Completado
- `WstunnelState.kt` — enum con STOPPED, STARTING, RUNNING, STOPPING, ERROR
- `WstunnelConfig.kt` — data class con Mode.FIXED/DYNAMIC, buildCommand(), isServerUrlValid
- `WstunnelManager.kt` — process manager con start/stop/isRunning, extractBinary, captureOutput
- `WstunnelManagerTest.kt` — 30 unit tests (command building, URL validation, state enums, equality)
- `app/src/main/assets/wstunnel_arm64` — placeholder para el binario (placeholder hasta que se ejecute el script)
- `scripts/download_wstunnel.sh` — script que descarga wstunnel v10.5.1 linux-arm64 de GitHub Releases

### Arquitectura
- **Extracción del binario**: al primer start() copia de `assets/wstunnel_arm64` a `filesDir/wstunnel`, `chmod +x`
- **ProcessBuilder**: sin root, `redirectErrorStream(false)` para capturar stdout/stderr por separado
- **Log capture**: threads daemon leyendo líneas con `BufferedReader`, output a Logcat (tag `wstunnel`) + buffer circular de 200 líneas
- **Shutdown**: SIGTERM → 3s espera → SIGKILL, join de threads de log
- **StateFlow**: MutableStateFlow<WstunnelState> expuesto como StateFlow inmutable
- **ERROR_PATTERN**: regex `(?i)(error|failed|panic|fatal|refused|timeout|ssl_err)` para detectar errores en stderr
- **DYNAMIC mode**: comando `udp://LOCAL_PORT?timeout_sec=0` (sin remoteHost:remotePort, el server determina el backend)

### Command generado (FIXED mode)
```
wstunnel client \
  -L udp://51820:72.62.160.61:51820?timeout_sec=0 \
  -p http://10.14.0.13:3128 \
  wss://solverius-ws.zpwhqo.easypanel.host \
  --connection-retry-max-backoff 10s \
  --websocket-ping-frequency 10s
```

### Estado inicial
- `WstunnelState.STOPPED` — valor inicial del StateFlow en el constructor de WstunnelManager
- `logBuffer` vacío al inicio, se llena con las líneas de stdout/stderr del proceso

### Tests implementados (30)
- Default config values: mode, port, URL, proxy, retry/ping (6 tests)
- Dynamic factory: `WstunnelConfig.dynamic()` (1 test)
- buildCommand FIXED: argument list, custom remote/proxy/URL/retry, element count (6 tests)
- buildCommand DYNAMIC: omit remote:port, custom port, proxy+retry preserved (3 tests)
- URL validation: wss/ws/http/empty/random (5 tests)
- WstunnelState enum: 5 values, valueOf (2 tests)
- Mode enum: FIXED/DYNAMIC, valueOf (1 test)
- Binary path logic (1 test)
- Config equality: identical, different modes, copy (3 tests)
- Total: 30 tests

### Pendiente
- `./gradlew test` no ejecutado — la descarga de Gradle 8.4 (130MB) no completa por red lenta
- El binario real de wstunnel debe descargarse con `./scripts/download_wstunnel.sh` antes del build
- Verificar compilación cuando el entorno de build esté disponible

DA3#16B|

A2E#19C|## T9 — VpnService with WireGuard Integration (2026-07-30)
DA3#ABE|
A40#C37|### Completado
8DB#C84|- `VpnConfig.kt` — data class for TUN configuration (address, prefixLength, mtu=1300, dnsServers, routes)
6A6#79F|- `WireGuardManager.kt` — GoBackend integration with StateFlow<WireGuardState>
F8E#4F6|- `VpnGatewayService.kt` — updated with startWithWireGuard(), shutdown(), onRevoke() cleanup
381#BC7|- `WireGuardManagerTest.kt` — 17 unit tests documenting protect() order, state machine, config building
DA3#18A|
6F2#60D|### protect() Pattern (CRITICAL for traffic loop prevention)
DA3#9B3|The correct order to avoid traffic loops when connecting SSTP:
DA3#E5B|1. Create socket: `SSLSocketFactory.getDefault().createSocket()`
220#119|2. Bind socket: `socket.bind(InetSocketAddress(0))` — assigns local port
6BC#4A5|3. **Call protect() BEFORE connect**: `vpnService.protectFileDescriptor(socket.getFileDescriptor())`
D0F#59D|4. Connect: `socket.connect(InetSocketAddress(sstpHost, sstpPort), timeout)`
400#C2C|Without protect(): SSTP connection goes through TUN → WireGuard → wstunnel → Internet → VPN server IP → LOOP
A42#415|With protect(): SSTP bypasses VPN, connects directly to SSTP server, then data flows through tunnel properly
DA3#9E4|
7C6#60D|### WireGuard State Machine
DA3#4FB|WireGuardState: STOPPED → STARTING → CONNECTED → STOPPING → ERROR
89F#1F4|GoBackend.setState(tunnel, State.UP, config) brings tunnel up
807#02B|GoBackend.setState(tunnel, State.DOWN, null) brings tunnel down
DA3#242|
B38#270|### WireGuard Endpoint = 127.0.0.1:51820
DA3#9FB|wstunnel forwards UDP:51820 to actual WireGuard server
A40#C0D|The peerEndpoint in WireGuardConfig is set to localhost because wstunnel handles the forwarding
610#8E9|WireGuard packets go: TUN → wstunnel client → wstunnel server (remote) → WireGuard server
DA3#052|
AE2#687|### VpnGatewayService Lifecycle
05F#B9F|onRevoke(): called when user disconnects or system kills VPN
2A5#2E7|- Calls shutdown() → stops WireGuard, closes TUN, cancels coroutines
82F#907|- Calls stopSelf() to destroy service
65F#4FF|shutdown(): clean shutdown of all VPN components
99F#623|startWithWireGuard(wgConfig, vpnConfig): initializes WireGuard tunnel
DA3#715|
A2E#19C|### Unit Tests (17 tests)
6C9#11B|- VpnConfig defaults: MTU=1300, DNS, routes validation
BCC#C05|- VpnConfig.isValid() edge cases (blank address, invalid prefix, MTU range)
BC8#AB9|- protect() order documentation
5F8#BB0|- WireGuard config string building
176#4DB|- State machine transitions
E75#4CB|- onRevoke shutdown sequence
60B#722|- Traffic loop prevention explanation
9BB#87D|- GoBackend endpoint = 127.0.0.1:51820
DA3#12F|
209#3E1|### Dependencies (already in build.gradle.kts)
19F#9DE|- com.wireguard.android:tunnel:1.0.20230706 ✅ (GoBackend, Tunnel, Config, State)
2B6#276|- kotlinx-coroutines-core:1.7.3 ✅
CB5#C86|- timber:5.0.1 ✅
DA3#D70|
634#DAC|### Files Created/Modified
39D#9A0|NEW: app/src/main/java/com/ucfvpn/app/vpn/VpnConfig.kt
8E3#416|NEW: app/src/main/java/com/ucfvpn/app/vpn/WireGuardManager.kt
B28#657|NEW: app/src/test/java/com/ucfvpn/app/vpn/WireGuardManagerTest.kt
D08#6D7|MODIFIED: app/src/main/java/com/ucfvpn/app/vpn/VpnGatewayService.kt
DA3#CFB|
86B#400|### Pending
B82#22C|./gradlew test — Gradle download timeout (same issue as T4, T6, T7, T8)
DA3#45C|
8A5#2BA|### Key Insight: Traffic Loop Prevention
2B3#2F2|The VPN needs to CONNECT to the SSTP server, but ALL traffic goes through the VPN by default.
DA3#A77|Without protect(), the SSTP connection would try to go through the VPN tunnel to reach the VPN server,
DA3#16B|creating a recursive loop. protect() is the solution: it exempts a specific socket from VPN routing.
## T10 — Global State Machine & Auto-Reconnection (2026-07-30)

### Completado
- `VpnState.kt` — sealed class con 11 estados (SstpConnecting, SstpConnected, ProxyAuthenticating, ProxyAuthenticated, WstunnelStarting, WstunnelRunning, WireGuardConnecting, WireGuardConnected, VpnStarting, VpnRunning + 4 estados de error)
- `VpnStateMachine.kt` — state machine con StateFlow<VpnState>, StateFlow<List<StateTransition>>, transición validada, historial de 20 transiciones, thread-safe con Mutex
- `ReconnectManager.kt` — exponential backoff (1s, 2s, 4s, 8s, 16s, 32s capped), ReconnectState (Idle/Waiting/Reconnecting/Stopped), configurable max attempts
- `VpnStateMachineTest.kt` — 27 tests: transiciones válidas/inválidas, historial, connect/disconnect, error recovery
- `ReconnectManagerTest.kt` — 14 tests: backoff progression, state updates, stop/start, reset

### Decisiones de diseño
- **Mutex para thread-safety**: todas las mutaciones del estado protegidas por `mutex.withLock`
- **Exponential backoff con bit shift**: `1L shl attempt` genera la secuencia 1,2,4,8,16,32...
- **MAX_ATTEMPTS = 0** significa infinite (configurable)
- **Error states → SstpConnecting** para auto-reconnect, pero user debe iniciar manualmente
- **isValidTransition** checks early for Disconnected (always valid) before exhaustive when
