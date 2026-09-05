# E2E Testing — UCF VPN (Fase 7, Tareas 7.1–7.3)

> **⚠️ Requiere dispositivo físico/emulador con acceso a la red UCF — no ejecutable en CI**

Los escenarios de esta guía validan el stack VPN completo (SSTP → Portal Auth → wstunnel → Split Tunnel) contra la infraestructura real de la UCF (`npv.ucf.edu.cu`, portal cautivo `internet.ucf.edu.cu`, proxy `10.14.0.13:3128`). Requieren conectividad a la red UCF y no pueden ejecutarse en GitHub Actions.

---

## Requisitos

| Requisito | Detalle |
|-----------|---------|
| **Dispositivo** | Android 10+ (API 29+) — físico o emulador |
| **Red** | Con acceso a la red UCF (WiFi/4G dentro del campus o con acceso al servidor SSTP) |
| **APK** | Debug APK del CI (`app-debug.apk` artifact de `.github/workflows/build.yml`) o `./gradlew assembleDebug` local |
| **Credenciales** | Usuario/contraseña SSTP + credenciales del portal cautivo UCF |
| **Herramientas** | `adb` (Android SDK platform-tools), `ping`, `curl`, `dig` (o `nslookup`), app de terminal en el dispositivo |

### Instalación

```bash
# Descargar el APK debug del artifact de CI y instalarlo
adb install -r app-debug.apk
```

### Logs (usar durante TODOS los tests)

```bash
# Capturar logs de todos los componentes del stack (tags del plan)
adb logcat -s "VpnOrchestrator" "SstpTunnelImpl" "PppStack" "WstunnelManager" "VpnGatewayService" "ProxyAuthService"

# Alternativa: volcar a archivo para análisis posterior
adb logcat -s "VpnOrchestrator" "SstpTunnelImpl" "PppStack" "WstunnelManager" "VpnGatewayService" "ProxyAuthService" > e2e-$(date +%Y%m%d-%H%M%S).log
```

---

## Tarea 7.1 — Test de Integración Completo

**Objetivo**: verificar el flujo completo WiFi → VPN → tráfico privado (SSTP) + tráfico internet (wstunnel SOCKS5) → desconexión limpia.

### Checklist

- [ ] **1. Conectar WiFi normal** — el dispositivo debe tener conectividad a la red UCF (verificar con `ping 8.8.8.8` antes de la VPN).
- [ ] **2. Iniciar VPN desde la app** — pulsar **Connect**. Esperar a que la UI muestre el estado `VpnRunning` (etapas: SSTP → PPP → Proxy Auth → wstunnel → VPN).
- [ ] **3. Verificar recurso privado**: `ping 10.x.x.x` (ej. `ping 10.14.0.13` — proxy HTTP) → debe responder **vía SSTP** (ruta privada por TUN).
- [ ] **4. Verificar internet**: `ping 8.8.8.8` → debe responder **vía wstunnel SOCKS5** (ruta default por TUN → hev-socks5-tunnel → wstunnel → proxy HTTP).
- [ ] **5. Verificar IP pública**: `curl https://httpbin.org/ip` → debe devolver la **IP del proxy HTTP de la UCF** (no la IP local del WiFi).
- [ ] **6. Verificar DNS**: `dig @1.1.1.1 google.com` (o `nslookup google.com 1.1.1.1`) → debe resolver **vía SOCKS5** (`dns.tcp: true` en el YAML dinámico).
- [ ] **7. Verificar UDP**: tráfico UDP (QUIC, DNS over UDP, o `ping` con paquetes UDP) → debe atravesar wstunnel.
- [ ] **8. Desconectar** — pulsar **Disconnect** → verificar cleanup completo en logcat (orden inverso: VPN → wstunnel → proxy → SSTP) y que el dispositivo recupera la conectividad WiFi normal.

### Resultados

| # | Verificación | Comando | Resultado esperado | Resultado observado | PASS/FAIL |
|---|--------------|---------|--------------------|--------------------|-----------|
| 1 | Conectividad previa | `ping 8.8.8.8` | Respuesta OK | | |
| 2 | Estado VPN | UI + logcat | `VpnRunning` alcanzado | | |
| 3 | Recurso privado | `ping 10.14.0.13` | Respuesta vía SSTP | | |
| 4 | Internet | `ping 8.8.8.8` | Respuesta vía wstunnel | | |
| 5 | IP pública | `curl https://httpbin.org/ip` | IP del proxy UCF | | |
| 6 | DNS | `dig @1.1.1.1 google.com` | Resolución vía SOCKS5 | | |
| 7 | UDP | tráfico UDP/QUIC | Atraviesa wstunnel | | |
| 8 | Desconexión | UI + logcat | Cleanup completo, WiFi OK | | |

---

## Tarea 7.2 — Test de Reconexión

**Objetivo**: verificar la resiliencia del stack ante fallos de capa (wstunnel, red física, sesión del portal cautivo).

### Checklist

- [ ] **1. Matar wstunnel** — con la VPN activa, matar el proceso wstunnel en el dispositivo:
  ```bash
  adb shell "su -c 'killall wstunnel'"   # dispositivo rooteado
  # o desde la app: forzar detención del proceso vía logcat/UI
  ```
  → Verificar en logcat que `VpnOrchestrator` detecta el fallo (`WstunnelError`) y **reintenta la capa wstunnel** automáticamente (`WstunnelStarting` → `WstunnelRunning`).
- [ ] **2. Cortar WiFi** — desactivar WiFi (o activar modo avión) con la VPN activa → verificar que `ReconnectManager` detecta la pérdida de red y **reconecta automáticamente** al restaurar WiFi (backoff exponencial).
- [ ] **3. Expirar portal cautivo** — con la VPN activa, invalidar la sesión del portal (esperar expiración o forzar logout) → verificar que `ProxyAuthService` **re-autentica automáticamente** (CSRF login) y el stack continúa.

### Resultados

| # | Escenario | Trigger | Resultado esperado | Resultado observado | PASS/FAIL |
|---|-----------|---------|--------------------|--------------------|-----------|
| 1 | Muerte wstunnel | `killall wstunnel` | Retry capa wstunnel automático | | |
| 2 | Pérdida WiFi | WiFi off/on | Reconexión automática con backoff | | |
| 3 | Portal expirado | Sesión invalidada | Re-auth automático del portal | | |

---

## Tarea 7.3 — Test de Bypass Apps

**Objetivo**: verificar el split tunneling por aplicación — las apps en `bypassApps` salen por la red física, el resto por la VPN.

### Checklist

- [ ] **1. Configurar bypass** — en la app, sección **Split Tunnel Configuration**, campo **Bypass Apps**: `com.whatsapp,com.android.chrome`. Guardar.
- [ ] **2. Conectar VPN** — iniciar la conexión y esperar `VpnRunning`.
- [ ] **3. Verificar WhatsApp** (`com.whatsapp`) — abrir WhatsApp y enviar/recibir un mensaje → el tráfico debe salir por **WiFi físico** (no VPN). Verificar con `adb shell dumpsys` o captura de red que no pasa por el TUN.
- [ ] **4. Verificar Chrome** (`com.android.chrome`) — abrir Chrome y navegar → el tráfico debe salir por **WiFi físico** (no VPN).
- [ ] **5. Verificar app NO bypass** — abrir una app que NO esté en la lista (ej. otra app con tráfico de red) → su tráfico debe ir **por la VPN** (IP del proxy UCF en `curl https://httpbin.org/ip` desde esa app).

### Resultados

| # | App | Paquete | Resultado esperado | Resultado observado | PASS/FAIL |
|---|-----|---------|--------------------|--------------------|-----------|
| 1 | WhatsApp | `com.whatsapp` | Tráfico por WiFi físico | | |
| 2 | Chrome | `com.android.chrome` | Tráfico por WiFi físico | | |
| 3 | App normal | (otra) | Tráfico por VPN | | |

---

## Referencia de tags logcat

| Tag | Componente | Qué observar |
|-----|------------|--------------|
| `VpnOrchestrator` | Orquestador | Secuencia de capas, retries, transiciones de estado |
| `SstpTunnelImpl` | Túnel SSTP | Handshake TLS/HTTP, CONNECTED, IP asignada (PPP) |
| `PppStack` | Stack PPP | LCP → PAP → IPCP, IP local 10.x |
| `WstunnelManager` | wstunnel | Extracción binario, lanzamiento, SOCKS5 ready |
| `VpnGatewayService` | VpnService | TUN establecido, rutas split, hev-socks5-tunnel |
| `ProxyAuthService` | Portal cautivo | Login CSRF, cookies de sesión, re-auth |

## Notas

- Los escenarios 7.1–7.3 requieren **dispositivo real/emulador con acceso a la red UCF** — bloqueo externo documentado (no ejecutable en CI).
- La parte automatizable de la Fase 7 (tests instrumented + CI) vive en:
  - `app/src/androidTest/java/com/ucfvpn/app/prefs/ConfigPreferencesInstrumentedTest.kt`
  - `app/src/androidTest/java/com/ucfvpn/app/wstunnel/HevSocks5TunnelConfigGeneratorInstrumentedTest.kt`
  - `app/src/androidTest/java/com/ucfvpn/app/wstunnel/WstunnelManagerInstrumentedTest.kt`
  - `.github/workflows/build.yml` (job `instrumented-tests`, emulador API 29)