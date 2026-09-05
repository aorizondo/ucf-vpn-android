# Plan: UCF VPN Android App — SSTP + Proxy Auth + wstunnel + WireGuard

## TL;DR

> **Resumen**: Aplicación Android nativa (Kotlin) que replica el setup de doble VPN actual en Linux. Conecta vía SSTP al VPN de la UCF, autentica en el portal cautivo, y establece WireGuard sobre WebSocket (wstunnel) como VPN final. Todo en una sola app sin root.

> **Entregables**:
> - App Android (APK) con stack VPN completo
> - Código fuente Kotlin en GitHub
> - GitHub Actions para CI/CD y builds automáticos
> - UI de configuración de credenciales (SSTP, proxy, WireGuard, wstunnel)

> **Esfuerzo estimado**: Grande (3-4 semanas de desarrollo)
> **Ejecución en paralelo**: SÍ — 4 waves
> **Ruta crítica**: Proto SSTP (Wave 1) → Integración PPP/SSTP (Wave 2) → Stack completo (Wave 3) → CI/CD (Wave 4)

---

## Contexto

### Petición Original
El usuario necesita una app Android que replique su setup actual en Linux:
1. SSTP → `npv.ucf.edu.cu:443` (VPN UCF)
2. Proxy auth → `internet.ucf.edu.cu` (portal cautivo con CSRF tokens)
3. wstunnel → `wss://solverius-ws.zpwhqo.easypanel.host` (WebSocket tunnel)
4. WireGuard → `72.62.160.61:51820` (sobre wstunnel, endpoint local `127.0.0.1:51820`)

### Setup Actual (Linux) Descubierto

| Componente | Detalle |
|-----------|---------|
| **SSTP Server** | npv.ucf.edu.cu (200.14.50.222:443) |
| **Conexión NM** | "VPN UCF" (UUID: adf268ce) |
| **Proxy UCF** | 10.180.0.30 (routing), 10.14.0.13:3128 (HTTP proxy para wstunnel) |
| **Portal Cautivo** | https://internet.ucf.edu.cu/auth/login |
| **WireGuard Peer** | 72.62.160.61:51820, IP local 10.8.0.2/24 |
| **wstunnel Server** | wss://solverius-ws.zpwhqo.easypanel.host |
| **Comando wstunnel** | `wstunnel client -L udp://51820:72.62.160.61:51820 -p http://10.14.0.13:3128 wss://solverius-ws.zpwhqo.easypanel.host --connection-retry-max-backoff 10s --websocket-ping-frequency 10s` |
| **Credenciales SSTP** | Configurables por usuario (no hardcodeadas) |

### Proyecto Existente
El usuario ya tiene una implementación Python en `/home/antonio/Desarrollo/solverius/sstp/`:
- Cliente SSTP completo con lwIP para PPP
- Handshake SSL/TLS con Crypto Binding (HMAC-SHA1)
- Framing HDLC para PPP sobre SSTP
- Se usará como referencia de protocolo para la implementación Kotlin

### Investigación Realizada
- `kittoku/Open-SSTP-Client` (⭐538) — Base Kotlin para SSTP Android
- wstunnel — Binarios ARM64 pre-compilados disponibles
- WireGuard Android — `.aar` oficial en Maven Central
- VpnService API — `protect()` para chained VPN sin loop
- Hev-socks5-tunnel / go-tun2socks — Para TUN→SOCKS5 si necesario

### Análisis Metis
Metis identificó como **riesgo crítico**: determinar si Open-SSTP-Client maneja PPP completo o solo transporte SSTP. Esto define si necesitamos NDK (lwIP) o podemos ir con Kotlin puro.

---

## Work Objectives

### Core Objective
Crear una aplicación Android que establezca un stacked VPN (SSTP → Portal Auth → wstunnel → WireGuard) para tunelizar todo el tráfico del dispositivo, replicando el setup actual en Linux.

### Entregables Concretos
- [ ] Código fuente en GitHub (`ucf-vpn-android`)
- [ ] APK debug release vía GitHub Actions
- [ ] APK release firmado para distribución
- [ ] Documentación de configuración

### Definition of Done
- [ ] App conecta SSTP a npv.ucf.edu.cu:443 exitosamente
- [ ] Proxy auth automático funciona (login portal cautivo)
- [ ] wstunnel se ejecuta y conecta al servidor remoto
- [ ] WireGuard establece tunnel (IP 10.8.0.2/24)
- [ ] Tráfico de apps pasa por el stack completo
- [ ] Reconexión automática en caída de SSTP
- [ ] Build automático en GitHub Actions produce APK

### Must Have
- Stack VPN funcional completo (SSTP → Auth → wstunnel → WireGuard)
- UI de configuración de credenciales (sin hardcodeo)
- Reconexión automática al caer SSTP
- Ignorar advertencias de certificados SSL en SSTP
- Modos wstunnel: fijo (config predefinida) y dinámico (configurable)
- Login automático de portal cautivo (y tras reconexión)
- Almacenamiento seguro de claves (Android Keystore para WG)
- Build automático con GitHub Actions
- API mínima: 26 (Android 8.0)
- Sin root

### Must NOT Have (Guardrails)
- NO implementar servidor SSTP (es de UCF)
- NO implementar servidor wstunnel (ya existe en solverius-ws.zpwhqo.easypanel.host)
- NO implementar servidor WireGuard
- NO split-tunneling en V1
- NO kill switch en V1
- NO múltiples perfiles en V1
- NO estadísticas/gráficos de tráfico en V1
- NO auto-start on boot en V1
- NO hardcodear credenciales de producción
- NO SharedPreferences para claves privadas WireGuard
- NO rootear el dispositivo

---

## Verification Strategy

> **ZERO HUMAN INTERVENTION** — Toda verificación es ejecutada por agentes. No se requiere intervención manual.

### Test Decision
- **Infraestructura tests**: SÍ (GitHub Actions + Gradle)
- **Tests automatizados**: TDD para módulos críticos (SSTP protocol, PPP, WireGuard config)
- **Framework**: JUnit 5 + MockK (Kotlin mocking)
- **UI Tests**: Compose UI Test donde aplique

### QA Policy
Cada tarea incluirá escenarios ejecutables por agente:
- **Módulos Kotlin**: `./gradlew test` + asserts específicos
- **Integración**: Scripts de bash que verifican conectividad
- **Build**: Verificar APK generado en GitHub Actions
- **Evidence**: Logs de build, test results, screenshots de UI

---

## Execution Strategy

### Parallel Execution Waves

```
Wave 1 (Fundación — arranque inmediato, 5 tareas paralelas):
├── T1: Repo + Gradle + GitHub Actions scaffold
├── T2: Módulo SSTP Protocol (traducción Python→Kotlin)
├── T3: Módulo PPP Handler (framing HDLC + lwIP/PPP)
├── T4: WireGuard config parser + almacenamiento seguro
└── T5: Diseño UI básico (Jetpack Compose)

Wave 2 (Núcleo — después de Wave 1, máximo paralelismo):
├── T6: SSTP Client completo (+ Open-SSTP-Client fork/adapt)
├── T7: Proxy Auth module (OkHttp + CSRF flow)
├── T8: wstunnel wrapper (extraer binary, ProcessBuilder, monitoreo)
├── T9: VpnService con protect() + integración WireGuard
└── T10: Máquina de estados + reconexión automática

Wave 3 (Integración — después de Wave 2):
├── T11: Integración stack completo (SSTP→Auth→wstunnel→WG→VpnService)
├── T12: UI de configuración + estados de conexión
├── T13: Logging interno + crash reporting básico
├── T14: Modo dinámico (wstunnel configurable)
└── T15: Pruebas de reconexión y edge cases

Wave 4 (CI/CD y release — después de Wave 3):
├── T16: GitHub Actions workflow completo
├── T17: Prueba de build APK + firma
├── T18: Documentación + README
└── T19: Release APK

Wave FINAL (4 revisiones paralelas, luego ok usuario):
├── F1: Plan Compliance Audit (oracle)
├── F2: Code Quality Review (unspecified-high)
├── F3: Real Manual QA (unspecified-high + Android testing)
└── F4: Scope Fidelity Check (deep)
```

---

## TODOs

### Wave 1 — Fundación

- [x] 1. **Repositorio + Gradle + GitHub Actions scaffold**

  **Qué hacer**:
  - Crear repositorio git local y remoto (GitHub: `ucf-vpn-android`)
  - Inicializar proyecto Android con Kotlin, Jetpack Compose, Gradle KTS
  - Configurar `build.gradle.kts` con:
    - Min SDK: 26, Target SDK: 34
    - Dependencias: WireGuard tunnel `.aar`
    - Dependencias: OkHttp, Compose, Coroutines
  - Crear GitHub Actions workflow básico (build + test)
  - Crear estructura de directorios del proyecto

  **Perfil recomendado**:
  - **Categoría**: `quick`
  - **Skills**: `[]`
  - **Razón**: Tarea de scaffolding, bien definida, sin complejidad técnica

  **Paralelización**:
  - **Parallel Group**: Wave 1 (con T2, T3, T4, T5)
  - **Bloquea**: Todas las tareas de Wave 2
  - **Bloqueado por**: Nada

  **Referencias**:
  - WireGuard .aar: `com.wireguard.android:tunnel` en Maven Central
  - Open-SSTP-Client Gradle: `github.com/kittoku/Open-SSTP-Client/blob/main/app/build.gradle`

  **Criterios de Aceptación**:
  - [ ] `./gradlew assembleDebug` produce APK
  - [ ] GitHub Actions corre `./gradlew build` exitosamente

  **QA Scenarios**:
  ```
  Scenario: Build debug APK
    Tool: Bash
    Steps:
      1. `cd project && git init && git add . && git commit -m "initial"`
      2. `ANDROID_HOME=/opt/android-sdk ./gradlew assembleDebug`
    Expected: BUILD SUCCESSFUL, APK en app/build/outputs/apk/debug/
    Evidence: .sisyphus/evidence/task-1-build.txt

  Scenario: GitHub Actions workflow syntax
    Tool: Bash
    Steps: `cd project && yamllint .github/workflows/*.yml`
    Expected: No errors
    Evidence: .sisyphus/evidence/task-1-gha.txt
  ```

  **Commit**: YES
  - Message: `chore: initialize Android project with Gradle and GH Actions`
  - Files: `.github/`, `app/`, `build.gradle.kts`, `settings.gradle.kts`

---

- [x] 2. **Módulo SSTP Protocol (traducción Python → Kotlin)**

  **Qué hacer**:
  - Traducir `sstp/protocol.py` a Kotlin (`SstpProtocol.kt`)
    - `SSTPVersion`, `SSTPMessageType`, `SSTPAttributeId` → enums
    - `SSTPPacket` → data class con `pack()`/`unpack()`
    - `SSTPControlPacket` → data class con atributos
    - `createCallConnectRequest()`, `createCallConnected()`, `createCryptoBindingAttribute()`
    - `createEchoRequest()`, `createPppDataPacket()`
  - Asegurar bit-exactitud: los paquetes generados deben ser idénticos byte-a-byte a los de `protocol.py`
  - Escribir tests unitarios comparando output con Python

  **Perfil Recomendado**:
  - **Categoría**: `deep`
  - **Skills**: `[]`
  - **Razón**: Traducción precisa de protocolo binario, requiere atención bit a bit

  **Paralelización**:
  - **Parallel Group**: Wave 1 (con T1, T3, T4, T5)
  - **Bloquea**: T6 (SSTP Client), T10 (máquina de estados)
  - **Bloqueado por**: Nada

  **Referencias**:
  - `/home/antonio/Desarrollo/solverius/sstp/sstp/protocol.py` — implementación original
  - `github.com/kittoku/Open-SSTP-Client/blob/main/app/src/main/java/kittoku/osc/unit/sstp/ControlPacket.kt` — referencia Kotlin

  **Criterios de Aceptación**:
  - [ ] Tests unitarios verifican que paquetes SSTP generados coinciden byte-a-byte con `protocol.py`
  - [ ] `./gradlew test` pasa todos los tests del módulo

  **QA Scenarios**:
  ```
  Scenario: SSTP packet byte-exact match with Python reference
    Tool: Bash
    Steps:
      1. Ejecutar Python: `python3 -c "from sstp.protocol import create_call_connect_request; print(create_call_connect_request().hex())"` (desde solverius/sstp/)
      2. Ejecutar test Kotlin que genera el mismo paquete
      3. Comparar hex strings
    Expected: Exact match
    Evidence: .sisyphus/evidence/task-2-byte-match.txt
  ```

  **Commit**: YES
  - Message: `feat: add SSTP protocol implementation (Kotlin port)`
  - Files: `app/src/main/java/.../sstp/protocol/SstpProtocol.kt`, tests/

---

- [x] 3. **Módulo PPP Handler (framing HDLC + lwIP/PPP)**

  **Qué hacer**:
  - Traducir `sstp/ppp_handler.py` a Kotlin (`PppHandler.kt`)
    - `HDLCHandler`: encode/decode con FCS-16 (CRC-CCITT)
    - `PPPHandler`: manejo de frames PPP entre SSTP y stack IP
    - Implementar FCS-16 lookup table (idéntica a la de Python)
  - Decidir estrategia PPP:
    - **Opción A**: Implementar PPP minimalista en Kotlin puro (LCP, IPCP, PAP/MS-CHAPv2)
    - **Opción B**: Compilar lwIP con NDK y usar JNI
    - **Por defecto**: Opción A (más portable, sin NDK), verificar viabilidad
  - Escribir tests unitarios con frames de prueba del proyecto Python

  **Perfil Recomendado**:
  - **Categoría**: `deep`
  - **Skills**: `[]`
  - **Razón**: Protocolo de red complejo (PPP, HDLC, FCS), requiere precisión

  **Paralelización**:
  - **Parallel Group**: Wave 1 (con T1, T2, T4, T5)
  - **Bloquea**: T6 (SSTP Client), T9 (VpnService)
  - **Bloqueado por**: Nada

  **Referencias**:
  - `/home/antonio/Desarrollo/solverius/sstp/sstp/ppp_handler.py` — FCS-16, HDLC encode/decode
  - `/home/antonio/Desarrollo/solverius/sstp/lwip_bindings/lwip_wrapper.py` — PPP via lwIP
  - RFC 1662: PPP in HDLC-like Framing
  - RFC 1332: PPP IPCP

  **Criterios de Aceptación**:
  - [ ] FCS-16 produce `0xf0b8` para datos válidos (self-check)
  - [ ] HDLC encode/decode roundtrip produce frame original
  - [ ] `./gradlew test` pasa

  **QA Scenarios**:
  ```
  Scenario: FCS-16 self-check
    Tool: Bash (Kotlin test)
    Steps: `./gradlew test --tests *PppHandlerTest`
    Expected: All tests pass, including FCS-16 GOODFCS check (0xf0b8)
    Evidence: .sisyphus/evidence/task-3-fcs.txt

  Scenario: HDLC encode/decode roundtrip
    Tool: Bash (Kotlin test)
    Steps: Frame de prueba → encode → decode → comparar
    Expected: Original frame after roundtrip
    Evidence: .sisyphus/evidence/task-3-hdlc.txt
  ```

  **Commit**: YES
  - Message: `feat: add PPP handler with HDLC framing and FCS-16`
  - Files: `app/src/main/java/.../sstp/ppp/PppHandler.kt`, tests/

---

- [x] 4. **WireGuard config parser + almacenamiento seguro**

  **Qué hacer**:
  - Implementar parser de configuración WireGuard (formato wg-quick)
  - Usar `com.wireguard.config.Config` del `.aar` oficial
  - Implementar almacenamiento seguro con Android Keystore:
    - PrivateKey, PreSharedKey → `KeyStore` (AndroidKeystore provider)
    - Config no sensible (endpoint, allowedIPs, DNS) → EncryptedSharedPreferences
  - Crear interfaz `WireGuardConfigRepository` (guardar/cargar/borrar)
  - Referencia de configuración:
    ```
    [Interface]
    PrivateKey = <de keystore>
    Address = 10.8.0.2/24
    DNS = 1.1.1.1, 2606:4700:4700::1111
    MTU = 1420

    [Peer]
    PublicKey = OVm14lotGvKKawksQ8UVPhO0phxZ+8WZDlxgAKZ55h0=
    PresharedKey = <de keystore>
    Endpoint = 127.0.0.1:51820
    AllowedIPs = 0.0.0.0/0, ::/0
    PersistentKeepalive = 0
    ```

  **Perfil Recomendado**:
  - **Categoría**: `unspecified-high`
  - **Skills**: `[]`
  - **Razón**: Integración con Android Keystore + API de WireGuard .aar

  **Paralelización**:
  - **Parallel Group**: Wave 1 (con T1, T2, T3, T5)
  - **Bloquea**: T9 (VpnService+WG), T12 (UI configuración)
  - **Bloqueado por**: Nada

  **Referencias**:
  - `com.wireguard.android:tunnel` — `.aar` en Maven Central
  - `github.com/WireGuard/wireguard-android/blob/main/tunnel/src/main/java/com/wireguard/config/Config.java`
  - `github.com/WireGuard/wireguard-android/blob/main/tunnel/src/main/java/com/wireguard/android/backend/GoBackend.java`

  **Criterios de Aceptación**:
  - [ ] Config se parsea correctamente desde string wg-quick format
  - [ ] PrivateKey se almacena en Android Keystore y es recuperable
  - [ ] `./gradlew test` pasa

  **QA Scenarios**:
  ```
  Scenario: WireGuard config parse + save/load
    Tool: Instrumented test (Android)
    Steps: 
      1. Crear config string desde template
      2. Parsear con Config.fromString()
      3. Guardar en repositorio
      4. Cargar y verificar campos
    Expected: Todos los campos coinciden
    Evidence: .sisyphus/evidence/task-4-wg-config.txt
  ```

  **Commit**: YES
  - Message: `feat: add WireGuard config parser and secure storage`
  - Files: `app/src/main/java/.../wg/`, tests/

---

- [x] 5. **Diseño UI básico (Jetpack Compose)**

  **Qué hacer**:
  - Crear estructura de pantallas con Jetpack Compose:
    - Pantalla de configuración (SSTP server, credenciales)
    - Pantalla de estado/conexión
    - Pantalla de logs
  - Implementar ViewModel con estado de conexión
  - No implementar lógica de conexión aún (solo UI)
  - Tema Material 3, modo claro/oscuro

  **Perfil Recomendado**:
  - **Categoría**: `visual-engineering`
  - **Skills**: `[]`
  - **Razón**: UI/UX con Jetpack Compose

  **Paralelización**:
  - **Parallel Group**: Wave 1 (con T1, T2, T3, T4)
  - **Bloquea**: T12 (UI completa)
  - **Bloqueado por**: Nada

  **Criterios de Aceptación**:
  - [ ] UI compila y se renderiza
  - [ ] Navegación entre pantallas funciona

  **QA Scenarios**:
  ```
  Scenario: UI renders correctly
    Tool: Bash (./gradlew connectedCheck) + screenshot
    Steps: Build and launch app on emulator
    Expected: Pantallas se renderizan sin crashes
    Evidence: .sisyphus/evidence/task-5-ui-screenshot.png
  ```

  **Commit**: YES
  - Message: `feat: add basic UI with Jetpack Compose`
  - Files: `app/src/main/java/.../ui/`

---

### Wave 2 — Núcleo

- [x] 6. **SSTP Client completo (fork/adapt Open-SSTP-Client)**

  **Qué hacer**:
  - Adaptar `kittoku/Open-SSTP-Client` para usar como módulo, no como app independiente
  - Separar SSTP de VpnService — SSTP será un `Service` normal (no VpnService)
  - Implementar:
    - TCP connect a `npv.ucf.edu.cu:443` con socket protegido
    - SSL/TLS handshake con `SSLContext` personalizado (VERIFY_NONE)
    - HTTP `SSTP_DUPLEX_POST` request
    - SSTP control message exchange (CALL_CONNECT_REQUEST → CALL_CONNECT_ACK)
    - Crypto Binding (HMAC-SHA1, export keying material)
    - PPP negotiation handler callback
    - ECHO_REQUEST keepalive periódico
  - Configurable: ignorar certificados SSL (TrustManager que acepta todo)
  - Exportar interfaz `SstpTunnel` con `InputStream`/`OutputStream` para datos PPP

  **Perfil Recomendado**:
  - **Categoría**: `deep`
  - **Skills**: `[]`
  - **Razón**: Protocolo complejo, SSL/TLS, Crypto Binding, integración con PPP

  **Paralelización**:
  - **Parallel Group**: Wave 2 (con T7, T8, T9, T10)
  - **Bloquea**: T11 (integración stack)
  - **Bloqueado por**: T2 (SSTP protocol), T3 (PPP handler)

  **Referencias**:
  - `github.com/kittoku/Open-SSTP-Client` — base Kotlin
  - `/home/antonio/Desarrollo/solverius/sstp/sstp/client.py` — orquestación
  - `/home/antonio/Desarrollo/solverius/sstp/sstp/handshake.py` — SSL + Crypto Binding

  **Criterios de Aceptación**:
  - [ ] `SstpTunnel.connect()` establece conexión completa a npv.ucf.edu.cu:443
  - [ ] SSL handshake exitoso con verify_none
  - [ ] SSTP CALL_CONNECT_REQUEST → CALL_CONNECT_ACK
  - [ ] Crypto Binding exitoso (HMAC-SHA1)
  - [ ] ECHO_REQUEST periódico funciona
  - [ ] `./gradlew test` pasa

  **QA Scenarios**:
  ```
  Scenario: SSTP connection to UCF server
    Tool: Instrumented test (Android con red UCF)
    Preconditions: Dispositivo en red UCF (o con SSTP reachable)
    Steps:
      1. Crear SstpTunnel con server=npv.ucf.edu.cu:443
      2. Llamar connect()
      3. Verificar SSL handshake completed
      4. Verificar CALL_CONNECT_ACK recibido
    Expected: Conexión establecida, callback PPP output recibido
    Evidence: .sisyphus/evidence/task-6-sstp-connect.txt

  Scenario: SSL certificate ignore
    Tool: Unit test con certificado inválido
    Steps:
      1. Conectar a servidor con cert auto-firmado
      2. TrustManager acepta todo
    Expected: SSL handshake exitoso a pesar de cert inválido
    Evidence: .sisyphus/evidence/task-6-ssl-ignore.txt
  ```

  **Commit**: YES (groups with T7)
  - Message: `feat: add SSTP client with SSL, crypto binding, and keepalive`
  - Files: `app/src/main/java/.../sstp/client/`

---

- [x] 7. **Proxy Auth Module (OkHttp + CSRF flow)**

  **Qué hacer**:
  - Implementar módulo de autenticación de portal cautivo:
    1. GET `https://internet.ucf.edu.cu/auth/login?next=/` → extraer `csrfmiddlewaretoken`
    2. POST con `csrfmiddlewaretoken`, `username`, `password`, `first_step=False`
    3. GET `https://internet.ucf.edu.cu/` → extraer `csrfmiddlewaretoken`
    4. POST con `csrfmiddlewaretoken`, `manual=Crear+una+sesion+para+este+dispositivo`
  - Usar OkHttp con cookie store persistente
  - Manejar expiración de sesión (re-autenticar si necesario)
  - Disparar automáticamente tras conexión SSTP y tras reconexión
  - Implementar como `ProxyAuthService` que expone estado (authenticating/authenticated/error)

  **Perfil Recomendado**:
  - **Categoría**: `unspecified-high`
  - **Skills**: `[]`
  - **Razón**: HTTP automation, manejo de cookies, sesiones

  **Paralelización**:
  - **Parallel Group**: Wave 2 (con T6, T8, T9, T10)
  - **Bloquea**: T8 (wstunnel necesita proxy), T11 (integración)
  - **Bloqueado por**: Nada (puede probarse en paralelo con mock)

  **Referencias**:
  - Script `proxy_login.sh` en `/etc/NetworkManager/dispatcher.d/adf268ce-37db-4720-9a06-70d036313dfb/proxy_login.sh`
  - OkHttp docs: `https://square.github.io/okhttp/`

  **Criterios de Aceptación**:
  - [ ] Login exitoso con credenciales de prueba
  - [ ] Manejo de cookies persistente
  - [ ] Re-autenticación automática si expira
  - [ ] `./gradlew test` pasa (con mocks)

  **QA Scenarios**:
  ```
  Scenario: Proxy auth flow (mocked)
    Tool: Unit test (MockWebServer)
    Steps:
      1. Mockear server que responde con CSRF tokens
      2. Ejecutar ProxyAuthService.login()
      3. Verificar secuencia GET→POST→GET→POST
    Expected: Login exitoso, session cookie almacenada
    Evidence: .sisyphus/evidence/task-7-proxy-auth.txt
  ```

  **Commit**: YES (groups with T6)
  - Message: `feat: add captive portal proxy authentication module`
  - Files: `app/src/main/java/.../proxy/`

---

- [x] 8. **wstunnel wrapper (binary embedding + subprocess)**

  **Qué hacer**:
  - Descargar wstunnel ARM64 binary de releases oficiales
  - Embeber en `app/src/main/assets/wstunnel`
  - Implementar `WstunnelManager`:
    - Extraer binary de assets a `context.filesDir`
    - Ejecutar con `ProcessBuilder` (no root)
    - Monitorear proceso (stdout/stderr)
    - Matar proceso limpiamente al parar
  - Configurar comando wstunnel:
    - **Modo fijo**: parámetros predefinidos (solo inicio/parada)
    - **Modo dinámico**: usuario configura server, puertos, etc.
  - Comando por defecto:
    ```
    wstunnel client \
      -L udp://51820:72.62.160.61:51820?timeout_sec=0 \
      -p http://10.14.0.13:3128 \
      wss://solverius-ws.zpwhqo.easypanel.host \
      --connection-retry-max-backoff 10s \
      --websocket-ping-frequency 10s
    ```

  **Perfil Recomendado**:
  - **Categoría**: `unspecified-high`
  - **Skills**: `[]`
  - **Razón**: Procesos nativos, monitoreo en Android

  **Paralelización**:
  - **Parallel Group**: Wave 2 (con T6, T7, T9, T10)
  - **Bloquea**: T11 (integración), T14 (modo dinámico)
  - **Bloqueado por**: T7 (proxy auth)

  **Referencias**:
  - `github.com/erebe/wstunnel/releases` — Binarios ARM64
  - Script `pre-up.sh` en `/etc/NetworkManager/dispatcher.d/ac39d308-a32d-46dd-898b-15537f3d0251/pre-up.sh`

  **Criterios de Aceptación**:
  - [ ] wstunnel binary se extrae correctamente de assets
  - [ ] Proceso se inicia y se mantiene vivo
  - [ ] Logs de wstunnel se capturan en Android logcat
  - [ ] wstunnel se detiene limpiamente al parar servicio

  **QA Scenarios**:
  ```
  Scenario: wstunnel binary extraction and execution
    Tool: Instrumented test (Android)
    Steps:
      1. WstunnelManager.start(config)
      2. Verificar proceso wstunnel está corriendo (ps)
      3. Verificar puerto UDP 51820 está en listening (ss/netstat no disponible en Android → check /proc/net/udp)
    Expected: wstunnel corriendo, puerto abierto
    Evidence: .sisyphus/evidence/task-8-wstunnel.txt

  Scenario: wstunnel clean shutdown
    Tool: Instrumented test
    Steps:
      1. start() → wait 3s → stop()
      2. Verificar proceso ya no existe
    Expected: Proceso terminado limpiamente
    Evidence: .sisyphus/evidence/task-8-shutdown.txt
  ```

  **Commit**: YES (groups with T9)
  - Message: `feat: add wstunnel wrapper with binary embedding and process management`
  - Files: `app/src/main/java/.../wstunnel/`, `app/src/main/assets/wstunnel`

---

- [x] 9. **VpnService con protect() + integración WireGuard**

  **Qué hacer**:
  - Implementar `VpnService` personalizado:
    - `Builder` con `addAddress("10.0.0.1", 24)`, `addRoute("0.0.0.0", 0)`
    - MTU: 1300 (triple overhead: SSTP + WS + WG)
    - DNS: 1.1.1.1, 8.8.8.8
  - Integrar WireGuard `GoBackend`:
    - Configurar con endpoint `127.0.0.1:51820` (wstunnel local)
    - Usar `backend.setState(tunnel, State.UP, config)`
  - **CRÍTICO**: `protect()` en socket SSTP ANTES de connect
    - Socket SSTP debe bind'earse antes de protect()
    - Verificar que tráfico SSTP NO pasa por el TUN
  - Manejar `onRevoke()` (usuario desconecta manualmente)
  - Estado UP/DOWN notificado al ViewModel

  **Perfil Recomendado**:
  - **Categoría**: `deep`
  - **Skills**: `[]`
  - **Razón**: Integración crítica VpnService + WireGuard + protect(), errores causan loops

  **Paralelización**:
  - **Parallel Group**: Wave 2 (con T6, T7, T8, T10)
  - **Bloquea**: T11 (integración stack)
  - **Bloqueado por**: T3 (PPP handler), T4 (WG config)

  **Referencias**:
  - `github.com/schwabe/ics-openvpn` — patrón VpnService + `protect()`
  - `github.com/WireGuard/wireguard-android/blob/main/tunnel/src/main/java/com/wireguard/android/backend/GoBackend.java`
  - Android `VpnService.Builder` docs

  **Criterios de Aceptación**:
  - [ ] VpnService establece TUN interface exitosamente
  - [ ] WireGuard GoBackend se inicia con endpoint localhost:51820
  - [ ] `protect()` evita loop de tráfico
  - [ ] `onRevoke()` detiene todo el stack limpiamente

  **QA Scenarios**:
  ```
  Scenario: VpnService + protect basic
    Tool: Instrumented test (Android emulator)
    Steps:
      1. Iniciar VpnService
      2. Verificar TUN interface creado (ip route show table all en emulador)
      3. Verificar socket SSTP no redirigido (tráfico sale por red física)
    Expected: TUN up, protect() funcionando
    Evidence: .sisyphus/evidence/task-9-vpn.txt

  Scenario: WireGuard GoBackend integration
    Tool: Unit test (mock)
    Steps:
      1. Configurar WireGuard con endpoint 127.0.0.1:51820
      2. backend.setState(tunnel, UP, config)
    Expected: GoBackend accepted config without errors
    Evidence: .sisyphus/evidence/task-9-wg.txt
  ```

  **Commit**: YES (groups with T8)
  - Message: `feat: add VpnService with protect() and WireGuard GoBackend integration`
  - Files: `app/src/main/java/.../vpn/`, `app/src/main/AndroidManifest.xml`

---

- [x] 10. **Máquina de estados + reconexión automática**

  **Qué hacer**:
  - Implementar máquina de estados del stack:
    ```
    DISCONNECTED → SSTP_CONNECTING → SSTP_CONNECTED 
    → PROXY_AUTHENTICATING → PROXY_AUTHENTICATED 
    → WSTUNNEL_STARTING → WSTUNNEL_RUNNING 
    → WIREGUARD_CONNECTING → WIREGUARD_CONNECTED 
    → VPN_STARTING → VPN_RUNNING
    ```
  - Estados de error: `SSTP_ERROR`, `PROXY_ERROR`, `WSTUNNEL_ERROR`, `WG_ERROR`
  - Reconexión automática:
    - Detectar caída de SSTP (socket closed, SSL error, timeout)
    - Backoff exponencial (1s, 2s, 4s, 8s, max 60s)
    - Re-ejecutar proxy auth tras reconexión SSTP
    - Re-iniciar wstunnel
    - Re-establecer WireGuard
  - Thread safety con Kotlin Coroutines + StateFlow
  - Callbacks a UI vía ViewModel

  **Perfil Recomendado**:
  - **Categoría**: `deep`
  - **Skills**: `[]`
  - **Razón**: Lógica de estado compleja, concurrencia, manejo de errores

  **Paralelización**:
  - **Parallel Group**: Wave 2 (con T6, T7, T8, T9)
  - **Bloquea**: T11 (integración)
  - **Bloqueado por**: T2 (SSTP protocol), T6 (SSTP client)

  **Criterios de Aceptación**:
  - [ ] Máquina de estados transiciona correctamente
  - [ ] Backoff exponencial funciona en reconexión
  - [ ] Reconexión completa (SSTP→Auth→ws→WG→VPN) tras caída simulada
  - [ ] `./gradlew test` pasa

  **QA Scenarios**:
  ```
  Scenario: State machine transitions
    Tool: Unit test
    Steps:
      1. Simular eventos: connect → sstp_connected → proxy_auth_ok → wstunnel_up → wg_connected
      2. Verificar cada transición en StateFlow
    Expected: Estados siguen secuencia correcta
    Evidence: .sisyphus/evidence/task-10-states.txt

  Scenario: Auto-reconnect on SSTP disconnect
    Tool: Integration test
    Steps:
      1. Stack en estado RUNNING
      2. Simular cierre de socket SSTP
      3. Esperar reconexión
    Expected: Stack vuelve a RUNNING en < 30s
    Evidence: .sisyphus/evidence/task-10-reconnect.txt
  ```

  **Commit**: YES (groups with T11)
  - Message: `feat: add state machine and auto-reconnect with exponential backoff`
  - Files: `app/src/main/java/.../state/`

---

### Wave 3 — Integración

- [x] 11. **Integración stack completo**

  **Qué hacer**:
  - Integrar todos los componentes:
    - `SstpTunnel` + `ProxyAuthService` + `WstunnelManager` + `WireGuardBackend` + `VpnService`
  - Implementar orquestador central `VpnOrchestrator`:
    - Secuencia: SSTP → Auth → wstunnel → WG → VpnService
    - Usar máquina de estados de T10
    - Coordinar reconexión automática
  - Verificar flujo de datos completo:
    - App → TUN → WireGuard → loopback:51820 → wstunnel → SSTP → proxy → red física
  - Manejar errores en cualquier etapa (reconexión desde el punto de falla)

  **Perfil Recomendado**:
  - **Categoría**: `deep`
  - **Skills**: `[]`
  - **Razón**: Integración de 5+ módulos complejos, debugging de flujo

  **Paralelización**:
  - **Parallel Group**: Wave 3 (con T12, T13, T14, T15)
  - **Bloquea**: T16 (CI/CD completo)
  - **Bloqueado por**: T6 (SSTP), T7 (proxy), T8 (wstunnel), T9 (VpnService), T10 (state)

  **Criterios de Aceptación**:
  - [ ] Stack completo se inicia y establece conexión
  - [ ] Tráfico de apps pasa por el stack
  - [ ] Desconexión limpia de todos los componentes
  - [ ] Reconexión automática funciona end-to-end

  **QA Scenarios**:
  ```
  Scenario: Full stack connection
    Tool: Instrumented test (Android emulator con mock server)
    Steps:
      1. Iniciar VpnOrchestrator
      2. Esperar estado RUNNING
      3. Verificar TUN interface, wstunnel process, WG status
    Expected: Stack completo operativo
    Evidence: .sisyphus/evidence/task-11-full-stack.txt

  Scenario: Clean disconnection
    Tool: Instrumented test
    Steps:
      1. Stack en RUNNING
      2. Llamar stop()
      3. Verificar: wstunnel not running, VpnService revoked, WG closed
    Expected: Todos los componentes detenidos limpiamente
    Evidence: .sisyphus/evidence/task-11-cleanup.txt
  ```

  **Commit**: YES (groups with T10)
  - Message: `feat: integrate full VPN stack orchestrator`
  - Files: `app/src/main/java/.../orchestrator/`

---

- [x] 12. **UI de configuración + estados de conexión**

  **Qué hacer**:
  - Completar UI Jetpack Compose con:
    - Formulario de configuración SSTP (server, puerto, usuario, contraseña)
    - Campos proxy (host, puerto, usuario, contraseña)
    - Configuración wstunnel (server URL, modo fijo/dinámico, puertos)
    - Indicador de estado (conectando, conectado, error, desconectado)
    - Botón conectar/desconectar
    - Logs en tiempo real
  - Conectar ViewModel a `VpnOrchestrator` via StateFlow
  - Persistir configuración con DataStore (no sensible) + Keystore (claves)

  **Perfil Recomendado**:
  - **Categoría**: `visual-engineering`
  - **Skills**: `[]`
  - **Razón**: UI/UX con Compose, integración con ViewModel

  **Paralelización**:
  - **Parallel Group**: Wave 3 (con T11, T13, T14, T15)
  - **Bloquea**: T18 (documentación)
  - **Bloqueado por**: T5 (UI básica), T9 (VpnService), T10 (state machine)

  **Criterios de Aceptación**:
  - [ ] UI renderiza todos los campos de configuración
  - [ ] Botón conectar inicia VpnOrchestrator
  - [ ] Estado en tiempo real se actualiza
  - [ ] Configuración se persiste entre sesiones

  **QA Scenarios**:
  ```
  Scenario: Configuration UI - save and load
    Tool: Instrumented test
    Steps:
      1. Rellenar formulario con datos de prueba
      2. Guardar configuración
      3. Cerrar y reabrir pantalla
    Expected: Campos persistentes
    Evidence: .sisyphus/evidence/task-12-ui-save.png

  Scenario: Connection state display
    Tool: Instrumented test
    Steps:
      1. Simular estado CONNECTING en ViewModel
      2. Verificar UI muestra indicador de conexión
    Expected: Estado reflejado en UI
    Evidence: .sisyphus/evidence/task-12-ui-state.png
  ```

  **Commit**: YES (groups with T13)
  - Message: `feat: complete configuration UI with connection state display`
  - Files: `app/src/main/java/.../ui/`, `app/src/main/java/.../viewmodel/`

---

- [x] 13. **Logging interno + manejo de errores**

  **Qué hacer**:
  - Implementar sistema de logging:
    - Logs internos con `Timber` o logger propio
    - Almacenar últimos N logs en memoria circular
    - Exportar logs a archivo para debugging
    - Mostrar en UI en tiempo real
  - Manejo de errores:
    - Capturar todas las excepciones en cada capa del stack
    - Clasificar: recoverable (reconnect) vs fatal (stop)
    - Notificar al usuario con mensajes comprensibles
  - Timeout detection para cada etapa

  **Perfil Recomendado**:
  - **Categoría**: `unspecified-high`
  - **Skills**: `[]`
  - **Razón**: Logging, error handling en producción

  **Paralelización**:
  - **Parallel Group**: Wave 3 (con T11, T12, T14, T15)
  - **Bloquea**: Nada
  - **Bloqueado por**: T10 (state machine)

  **Criterios de Aceptación**:
  - [ ] Logs se capturan en todas las capas
  - [ ] Errores se clasifican correctamente
  - [ ] Logs visibles en UI de logs

  **QA Scenarios**:
  ```
  Scenario: Error logging and UI display
    Tool: Instrumented test
    Steps:
      1. Simular error en SSTP connection
      2. Verificar log capturado
      3. Verificar log visible en UI
    Expected: Error logged and displayed
    Evidence: .sisyphus/evidence/task-13-logs.txt
  ```

  **Commit**: YES (groups with T12)
  - Message: `feat: add logging and error handling system`
  - Files: `app/src/main/java/.../logging/`

---

- [x] 14. **Modo dinámico wstunnel**

  **Qué hacer**:
  - Implementar UI y lógica para modo dinámico de wstunnel:
    - Campos editables: server URL, proxy, puertos locales/remotos
    - Validación de formato de URL y puertos
    - Persistencia de configuración dinámica
  - Refactorizar `WstunnelManager` para aceptar configuración completa
  - Modo fijo: parámetros predefinidos (solo inicio/parada)
  - Modo dinámico: todos los parámetros configurables

  **Perfil Recomendado**:
  - **Categoría**: `unspecified-high`
  - **Skills**: `[]`
  - **Razón**: Configuración dinámica, validación

  **Paralelización**:
  - **Parallel Group**: Wave 3 (con T11, T12, T13, T15)
  - **Bloquea**: Nada (puede ser V2, pero incluido por solicitud)
  - **Bloqueado por**: T8 (wstunnel wrapper)

  **Criterios de Aceptación**:
  - [ ] UI de modo dinámico permite configurar todos los parámetros
  - [ ] Validación de campos funciona
  - [ ] Configuración se persiste

  **QA Scenarios**:
  ```
  Scenario: Dynamic mode configuration
    Tool: Instrumented test
    Steps:
      1. Seleccionar modo dinámico
      2. Configurar server, proxy, puertos personalizados
      3. Guardar y verificar wstunnel se inicia con parámetros personalizados
    Expected: wstunnel usa configuración personalizada
    Evidence: .sisyphus/evidence/task-14-dynamic.txt
  ```

  **Commit**: YES (groups with T15)
  - Message: `feat: add dynamic wstunnel configuration mode`
  - Files: `app/src/main/java/.../wstunnel/`, `app/src/main/java/.../ui/`

---

- [x] 15. **Pruebas de reconexión y edge cases**

  **Qué hacer**:
  - Escribir tests de integración para:
    - Reconexión tras caída de red WiFi
    - Cambio de red (WiFi → 4G)
    - Timeout de SSTP (servidor no responde)
    - Error de autenticación (credenciales inválidas)
    - Caída de wstunnel (servidor remoto no disponible)
    - Error de WireGuard (config inválida)
  - Implementar mocks para simular cada condición
  - Verificar máquina de estados transiciona correctamente en cada caso

  **Perfil Recomendado**:
  - **Categoría**: `unspecified-high`
  - **Skills**: `[]`
  - **Razón**: Testing de integración, edge cases

  **Paralelización**:
  - **Parallel Group**: Wave 3 (con T11, T12, T13, T14)
  - **Bloquea**: Nada
  - **Bloqueado por**: T10 (state machine), T11 (integración)

  **Criterios de Aceptación**:
  - [ ] Todos los tests de reconexión pasan
  - [ ] Edge cases documentados

  **QA Scenarios**:
  ```
  Scenario: Network change test
    Tool: Integration test
    Steps:
      1. Stack en RUNNING
      2. Desconectar WiFi (simular)
      3. Esperar reconexión automática
    Expected: Stack detecta caída, reconecta completamente
    Evidence: .sisyphus/evidence/task-15-reconnect.txt
  ```

  **Commit**: YES (groups with T14)
  - Message: `test: add reconnection and edge case tests`
  - Files: `app/src/test/`

---

### Wave 4 — CI/CD y Release

- [x] 16. **GitHub Actions workflow completo**

  **Qué hacer**:
  - Crear workflow `build.yml`:
    - Trigger: push a main, PRs
    - Job 1: Build + Unit Tests
      - Ubuntu latest, Android SDK 34
      - `./gradlew build test`
    - Job 2: UI Tests (emulator)
      - API 29 emulator
      - `./gradlew connectedCheck`
    - Job 3: APK Build
      - `./gradlew assembleDebug`
      - Upload APK as artifact
  - Configurar SDK Manager para instalar herramientas necesarias
  - NDK setup si se requiere (para lwIP)
  - Cache de Gradle para builds rápidos

  **Perfil Recomendado**:
  - **Categoría**: `quick`
  - **Skills**: `[]`
  - **Razón**: CI/CD, YAML, automatización

  **Paralelización**:
  - **Parallel Group**: Wave 4 (con T17, T18, T19)
  - **Bloquea**: T19 (release)
  - **Bloqueado por**: T1 (scaffold), T11 (integración)

  **Criterios de Aceptación**:
  - [ ] GitHub Actions corre build completo exitosamente
  - [ ] APK disponible como artifact
  - [ ] Tests unitarios se ejecutan en CI

  **QA Scenarios**:
  ```
  Scenario: CI build
    Tool: GitHub Actions (push simulado)
    Steps:
      1. Push a main
      2. Verificar workflow corre
    Expected: Build + test + APK artifact
    Evidence: .sisyphus/evidence/task-16-ci.txt
  ```

  **Commit**: YES
  - Message: `ci: add GitHub Actions workflow for build, test, and APK`
  - Files: `.github/workflows/build.yml`

---

- [x] 17. **Prueba de build APK + firma**

  **Qué hacer**:
  - Verificar APK se genera correctamente
  - Configurar signing para release:
    - Generar keystore para release
    - Configurar `signingConfigs` en Gradle
    - GitHub Actions secrets para credenciales de firma
  - Probar instalación en emulator/device
  - Verificar permisos: BIND_VPN_SERVICE, INTERNET

  **Perfil Recomendado**:
  - **Categoría**: `quick`
  - **Skills**: `[]`
  - **Razón**: Build configuration, signing

  **Paralelización**:
  - **Parallel Group**: Wave 4 (con T16, T18, T19)
  - **Bloquea**: T19 (release)
  - **Bloqueado por**: T1 (scaffold)

  **Criterios de Aceptación**:
  - [ ] APK release firmado se genera
  - [ ] APK se instala en dispositivo
  - [ ] Permisos VPN funcionan

  **QA Scenarios**:
  ```
  Scenario: APK generation and installation
    Tool: Bash + ADB
    Steps:
      1. ./gradlew assembleRelease
      2. adb install app/build/outputs/apk/release/app-release.apk
    Expected: APK installed successfully
    Evidence: .sisyphus/evidence/task-17-apk.txt
  ```

  **Commit**: YES (groups with T19)
  - Message: `chore: configure APK signing and release build`
  - Files: `app/build.gradle.kts`, `keystore.properties`

---

- [x] 18. **Documentación + README**

  **Qué hacer**:
  - README.md con:
    - Descripción del proyecto
    - Arquitectura (diagrama de flujo)
    - Requisitos: Android 8.0+, wstunnel server, WireGuard server
    - Cómo configurar
    - Cómo buildear localmente
    - Cómo contribuir
  - Documentación de configuración:
    - Campos de configuración explicados
    - Ejemplos de configuraciones
  - Diagrama de arquitectura (Mermaid)

  **Perfil Recomendado**:
  - **Categoría**: `writing`
  - **Skills**: `[]`
  - **Razón**: Documentación técnica

  **Paralelización**:
  - **Parallel Group**: Wave 4 (con T16, T17, T19)
  - **Bloquea**: Nada
  - **Bloqueado por**: T11 (integración)

  **Criterios de Aceptación**:
  - [ ] README completo con toda la información
  - [ ] Diagrama de arquitectura incluido

  **Commit**: YES (groups with T19)
  - Message: `docs: add comprehensive README and architecture documentation`
  - Files: `README.md`

---

- [x] 19. **Release APK**

  **Qué hacer**:
  - Tag de release en GitHub
  - Build de APK release firmado
  - Publicar release en GitHub Releases
  - Verificar APK descargable e instalable
  - Documentar release notes

  **Perfil Recomendado**:
  - **Categoría**: `quick`
  - **Skills**: `[]`
  - **Razón**: Release management

  **Paralelización**:
  - **Parallel Group**: Wave 4 (con T16, T17, T18)
  - **Bloquea**: F1-F4 (revisiones finales)
  - **Bloqueado por**: T16 (CI/CD), T17 (signing)

  **Criterios de Aceptación**:
  - [ ] Release publicado en GitHub
  - [ ] APK descargable y firmado

  **QA Scenarios**:
  ```
  Scenario: Release verification
    Tool: Bash
    Steps:
      1. gh release view
      2. Verificar APK en assets
    Expected: Release con APK firmado
    Evidence: .sisyphus/evidence/task-19-release.txt
  ```

  **Commit**: YES (groups with T17, T18)
  - Message: `chore: v1.0.0 release`
  - Files: (tag release)

---

### Wave FINAL — Verificación

- [x] F1. **Plan Compliance Audit** — `oracle`
  Leer el plan y el código final. Verificar que todos los "Must Have" están implementados. Buscar patrones prohibidos de "Must NOT Have". Verificar archivos de evidencia.
  Output: `Must Have [N/N] | Must NOT Have [N/N] | VERDICT: APPROVE/REJECT`

- [x] F2. **Code Quality Review** — `unspecified-high`
  `./gradlew lint`, `./gradlew test`, revisar código por `as any`, `@Suppress`, comentarios excesivos, hardcodeo.
  Output: `Lint [PASS/FAIL] | Tests [N/N] | VERDICT`

- [x] F3. **Real Manual QA** — `unspecified-high` (+ `playwright` skill si hay UI web)
  Probar APK en emulator/device real. Verificar cada escenario QA de cada tarea. Probar stack completo.
  Output: `Scenarios [N/N pass] | Integration [N/N] | VERDICT`

- [x] F4. **Scope Fidelity Check** — `deep`
  Verificar que se construyó exactamente lo especificado (ni más, ni menos). Detectar scope creep.
  Output: `Tasks [N/N compliant] | Contamination [CLEAN] | VERDICT`

---

## Commit Strategy

| Tareas | Mensaje |
|--------|---------|
| T1 | `chore: initialize Android project with Gradle and GH Actions` |
| T2 | `feat: add SSTP protocol implementation (Kotlin port)` |
| T3 | `feat: add PPP handler with HDLC framing and FCS-16` |
| T4 | `feat: add WireGuard config parser and secure storage` |
| T5 | `feat: add basic UI with Jetpack Compose` |
| T6+T7 | `feat: add SSTP client and proxy authentication module` |
| T8+T9 | `feat: add wstunnel wrapper and VpnService with WireGuard` |
| T10+T11 | `feat: add state machine, auto-reconnect, and stack orchestrator` |
| T12+T13 | `feat: complete UI with connection state and logging` |
| T14+T15 | `feat: add dynamic wstunnel mode and integration tests` |
| T16 | `ci: add GitHub Actions workflow for build, test, and APK` |
| T17+T18+T19 | `chore: v1.0.0 release with signing and documentation` |

---

## Success Criteria

### Verification Commands
```bash
# Build
./gradlew assembleDebug       # Expected: BUILD SUCCESSFUL
./gradlew test                 # Expected: All tests pass

# Install
adb install app/build/outputs/apk/debug/app-debug.apk   # Expected: Success

# GitHub Actions
git push origin main           # Expected: CI workflow runs successfully
```

### Final Checklist
- [ ] TUN interface se crea (VpnService)
- [ ] SSTP conecta a npv.ucf.edu.cu:443
- [ ] Proxy auth completa login en internet.ucf.edu.cu
- [ ] wstunnel proceso en ejecución
- [ ] WireGuard endpoint = 127.0.0.1:51820
- [ ] Tráfico fluye: App → TUN → WG → ws → SSTP → red física
- [ ] Reconexión automática funciona
- [ ] UI muestra estado correcto
- [ ] GitHub Actions produce APK
- [ ] Sin fugas de tráfico fuera del VPN
