# Plan: Testing wstunnel en Android — Progresivo (socks5 → socks5+VPN → WireGuard)

## TL;DR

> **Objetivo**: Probar wstunnel en Android de forma progresiva, empezando por socks5 manual hasta llegar al stack completo con WireGuard.

> **Secuencia**:
> 1. **Fase 1**: wstunnel socks5 → configurar proxy manual en browser/app *(1 día)*
> 2. **Fase 2**: wstunnel socks5 + VpnService (tun2socks) → tráfico completo *(3-5 días)*
> 3. **Fase 3**: wstunnel UDP forward + WireGuard → stack completo *(ya implementado)*

> **Esfuerzo total**: 4-7 días

---

## Fase 1: wstunnel socks5 (testing manual)

### Objetivo
Ejecutar wstunnel en socks5 mode y configurar manualmente el proxy en un browser o app para verificar que el tunnel WebSocket funciona a través del proxy HTTP de UCF.

### Binario wstunnel
- **Versión**: v10.5.1 (confirmada en `scripts/download_wstunnel.sh` línea 21)
- **Soporte socks5**: ✅ Confirmado por documentación de wstunnel
- **Flags soportados**: `--websocket-ping-frequency`, `--connection-retry-max-backoff` (ambos en help output)

### Comando wstunnel (testing manual en Termux)
```bash
wstunnel client \
  -L socks5://0.0.0.0:1080 \
  -p http://10.14.0.13:3128 \
  wss://solverius-ws.zpwhqo.easypanel.host \
  --websocket-ping-frequency 10s \
  --connection-retry-max-backoff 10s \
  --log-lvl DEBUG
```

### Auth del proxy
El flag `-p http://proxy:3128` se usa **sin credenciales**. Esto coincide con el modo UDP actual (línea 63 de `WstunnelConfig.kt`). El proxy de UCF probablemente no exige auth por conexión (o el portal cautivo la resuelve a nivel de red). **A confirmar en testing**: si wstunnel falla con 407 Proxy Authentication Required, agregar `--http-proxy-login` y `--http-proxy-password`.

### Cambios de código necesarios

#### 1. Agregar `TunnelType` a `WstunnelConfig.kt`

```kotlin
// Nuevo enum para tipo de tunnel
enum class TunnelType {
    UDP,      // udp://LOCAL:REMOTE:PORT ( WireGuard)
    SOCKS5,   // socks5://0.0.0.0:PORT (proxy local)
    HTTP,     // http://0.0.0.0:PORT (proxy HTTP local)
    TCP       // tcp://LOCAL:REMOTE:PORT (forward TCP fijo)
}
```

#### 2. Modificar `WstunnelConfig` data class

```kotlin
data class WstunnelConfig(
    val tunnelType: TunnelType = TunnelType.UDP,  // NUEVO
    val mode: Mode = Mode.FIXED,
    val localPort: Int = 51820,
    val remoteHost: String = "72.62.160.61",
    val remotePort: Int = 51820,
    val serverUrl: String = "wss://solverius-ws.zpwhqo.easypanel.host",
    val proxyHost: String = "10.14.0.13",
    val proxyPort: Int = 3128,
    val retryMaxBackoff: String = "10s",
    val websocketPingFrequency: String = "10s",
    val logLevel: String = "INFO"  // NUEVO: configurable log level
) {
    // ... Mode enum existente ...

    companion object {
        /** Factory para modo socks5 (testing manual) */
        fun socks5(
            localPort: Int = 1080,
            serverUrl: String = "wss://solverius-ws.zpwhqo.easypanel.host",
            proxyHost: String = "10.14.0.13",
            proxyPort: Int = 3128,
            logLevel: String = "INFO"
        ): WstunnelConfig = WstunnelConfig(
            tunnelType = TunnelType.SOCKS5,
            localPort = localPort,
            serverUrl = serverUrl,
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            logLevel = logLevel
        )

        fun dynamic(): WstunnelConfig = WstunnelConfig(mode = Mode.DYNAMIC)
    }
}
```

#### 3. Modificar `buildCommand()` en `WstunnelConfig.kt`

```kotlin
fun buildCommand(binaryPath: String): List<String> {
    val listenArg = when (tunnelType) {
        TunnelType.UDP -> when (mode) {
            Mode.FIXED -> "udp://${localPort}:${remoteHost}:${remotePort}?timeout_sec=0"
            Mode.DYNAMIC -> "udp://${localPort}?timeout_sec=0"
        }
        TunnelType.SOCKS5 -> "socks5://0.0.0.0:${localPort}"
        TunnelType.HTTP -> "http://0.0.0.0:${localPort}"
        TunnelType.TCP -> when (mode) {
            Mode.FIXED -> "tcp://${localPort}:${remoteHost}:${remotePort}"
            Mode.DYNAMIC -> throw IllegalArgumentException("TCP requires FIXED mode")
        }
    }

    return listOf(
        binaryPath,
        "client",
        "-L", listenArg,
        "-p", "http://${proxyHost}:${proxyPort}",
        serverUrl,
        "--connection-retry-max-backoff", retryMaxBackoff,
        "--websocket-ping-frequency", websocketPingFrequency,
        "--log-lvl", logLevel  // NUEVO: log level configurable
    )
}
```

### Punto de inyección en la app (Fase 1 Opción B)

En `VpnOrchestrator.connect()` (línea 254-286), el `WstunnelConfig` se construye en línea 275. Para testing de socks5, hay **dos opciones**:

**Opción A (Recomendada para testing rápido): Hardcoded temporal**
```kotlin
// En connect(), línea 275, cambiar temporalmente:
wstunnelConfig = WstunnelConfig.socks5(
    localPort = 1080,
    serverUrl = uiConfig.wstunnelUrl,
    proxyHost = "10.14.0.13",
    proxyPort = 3128,
    logLevel = "DEBUG"
)
```

**Opción B (Para testing iterativo): Flag debug en AppConfig**
```kotlin
// Agregar a AppConfig:
data class AppConfig(
    // ... campos existentes ...
    val debugWstunnelSocks5: Boolean = false  // NUEVO
)

// En connect(), línea 275:
wstunnelConfig = if (uiConfig.debugWstunnelSocks5) {
    WstunnelConfig.socks5(localPort = 1080, serverUrl = uiConfig.wstunnelUrl)
} else {
    WstunnelConfig(
        serverUrl = uiConfig.wstunnelUrl,
        mode = when (uiConfig.wstunnelMode) {
            WstunnelMode.FIXED -> WstunnelConfig.Mode.FIXED
            WstunnelMode.DYNAMIC -> WstunnelConfig.Mode.DYNAMIC
        }
    )
}
```

### Testing en Android

#### Opción A: Termux (más rápido para testing)
```bash
# En Termux
pkg install wget
wget https://github.com/erebe/wstunnel/releases/download/v10.5.1/wstunnel-linux-arm64
chmod +x wstunnel-linux-arm64
./wstunnel-linux-arm64 client \
  -L socks5://0.0.0.0:1080 \
  -p http://10.14.0.13:3128 \
  wss://solverius-ws.zpwhqo.easypanel.host \
  --websocket-ping-frequency 10s \
  --log-lvl DEBUG
```

#### Opción B: App (modificar WstunnelConfig)
1. Cambiar `WstunnelConfig` para usar `tunnelType = TunnelType.SOCKS5` (ver punto de inyección arriba)
2. Build e instalar APK
3. wstunnel arranca en socks5://0.0.0.0:1080

#### Verificar funcionamiento
```bash
# En otra terminal (o browser con proxy configurado)
curl -x socks5h://127.0.0.1:1080 https://httpbin.org/ip
# Debe retornar la IP del servidor wstunnel, no la IP local
```

### Browser proxy config
En Firefox/Chrome:
- Settings → Network → Manual proxy
- SOCKS Host: `127.0.0.1`, Port: `1080`
- SOCKS v5
- ✅ "Proxy DNS when using SOCKS v5"

### Criterios de éxito Fase 1
- [ ] wstunnel inicia sin errores en logs
- [ ] `curl -x socks5h://127.0.0.1:1080 https://httpbin.org/ip` retorna IP remota
- [ ] Browser puede navegar con proxy socks5 configurado
- [ ] Conexión se mantiene estable (ping/pong funciona)
- [ ] No hay errores 407 (proxy auth) en logs de wstunnel

---

## Fase 2: wstunnel socks5 + VpnService (tun2socks)

### Objetivo
Redirigir TODO el tráfico del dispositivo Android a través del socks5 de wstunnel usando VpnService + tun2socks. Sin WireGuard.

### Arquitectura
```
App Android → TUN interface (VpnService)
  → tun2socks (TUN → SOCKS5)
  → wstunnel socks5 (127.0.0.1:1080)
  → WebSocket → wstunnel server → Internet
```

### ⚠️ Problemas bloqueantes (resueltos)

#### 1. DNS a través del tunnel — RESUELTO
**Problema**: tun2socks solo proxya TCP. UDP DNS (puerto 53) entra al TUN y tun2socks no las reenvía.

**Solución**: Usar `hev-socks5-tunnel` (no `xjasonlyu/tun2socks`). Soporta:
- DNS proxy integrado (`--dns-server 127.0.0.1:5353`)
- Proxy UDP a través de SOCKS5
- Recibe fd TUN desde VpnService (no crea su propio dispositivo)

Comando tun2socks:
```bash
hev-socks5-tunnel -c /path/to/config.yaml
```

Config YAML:
```yaml
tunnel:
  device: auto          # Recibe fd desde VpnService
  stack: lwip           # lwIP stack para TUN
  dns:
    address: 127.0.0.1:5353
    name: 8.8.8.8       # DNS a resolver vía SOCKS5

proxy:
  server: 127.0.0.1:1080  # wstunnel socks5
  username: ""
  password: ""
```

#### 2. Loop de tráfico / protect() — RESUELTO
**Problema**: Cuando VpnService pone `0.0.0.0/0`, todo el tráfico (incluido wstunnel socket) se enruta al TUN → loop infinito.

**Solución**: Excluir IPs del proxy y server wstunnel de las rutas VPN usando `Builder.addRoute()` por rangos específicos en lugar de `0.0.0.0/0`, O usar `Builder.addDisallowedApplication()` para el paquete de wstunnel (si es la misma app).

**Implementación en `VpnGatewayService.kt`**:
```kotlin
// En establishTunInterface(), Builder.addRoute()现状:
// TODO: Para modo socks5+VPN, agregar:
// 1. Excluir IP del proxy UCF de las rutas
// 2. Excluir IP del server wstunnel de las rutas
// 3. O usar protect() en el socket de wstunnel (ya existe protectSocket())
```

**Detalle técnico**: wstunnel corre como subprocess de la app. Cuando la app crea el TUN, el socket TCP de wstunnel se protege con `protect()` ANTES de conectar al proxy. Pero wstunnel es un proceso separado — la app no posee sus fds.

**Solución alternativa**: Usar `Builder.addRoute()` con rangos CIDR específicos que excluyan las IPs de infraestructura:
```kotlin
// En VpnGatewayService.establishTunInterface():
builder.addRoute("0.0.0.0", 1)  // 0.0.0.0/1
builder.addRoute("128.0.0.0", 1) // 128.0.0.0/1
// Luego excluir las IPs de proxy/server con rutas más específicas
// O usar protect() en el fd del socket de wstunnel
```

#### 3. Integración tun2socks — RESUELTO
**Decisión**: Usar `hev-socks5-tunnel` (C, ligero, ~500KB ARM64).

**Modelo de integración**:
1. `VpnGatewayService` crea TUN fd via `VpnService.Builder.establish()`
2. `Tun2SocksManager` recibe el fd TUN y lo pasa a `hev-socks5-tunnel`
3. `hev-socks5-tunnel` usa lwIP para manejar tráfico TUN → SOCKS5
4. DNS se resuelve vía SOCKS5 (proxy DNS integrado)

**Binario**: Descargar de https://github.com/heiher/hev-socks5-tunnel/releases (ARM64).

### Componentes necesarios

1. **hev-socks5-tunnel**: Binario ARM64 (~500KB)
   - URL: https://github.com/heiher/hev-socks5-tunnel/releases
   - Config YAML embebida en assets/

2. **VpnGatewayService**: Ya existe con `establishTunInterface()` y `protectSocket/Fd`
   - Necesita modificación para soportar tun2socks (no solo WireGuard)

3. **Secuencia de arranque**:
   ```
   1. SSTP connect → portal auth
   2. wstunnel start (socks5 mode)
   3. tun2socks start (recibe fd TUN de VpnService)
   4. VpnService establish TUN
   5. Todo el tráfico fluye por el tunnel
   ```

### Cambios de código necesarios

#### Nuevo: `Tun2SocksManager.kt`
```kotlin
class Tun2SocksManager(private val context: Context) {
    // Similar a WstunnelManager pero para hev-socks5-tunnel binary
    // Extraer binario de assets a filesDir
    // Ejecutar: hev-socks5-tunnel -c /path/to/config.yaml
    // Recibe fd TUN desde VpnService
}
```

#### Nuevo: `hev_socks5_tunnel.yaml` en assets/
```yaml
tunnel:
  device: fd://3          # fd TUN pasado desde VpnService
  stack: lwip
  dns:
    address: 127.0.0.1:5353
    name: 8.8.8.8

proxy:
  server: 127.0.0.1:1080
  username: ""
  password: ""
```

#### Modificar: `VpnGatewayService.kt`
```kotlin
// Nuevo método para modo socks5+VPN
fun startWithSocks5Vpn(wstunnelConfig: WstunnelConfig): Result<Unit> {
    // 1. Establecer TUN interface
    // 2. Pasar fd TUN a Tun2SocksManager
    // 3. Iniciar tun2socks
    // 4. Retornar resultado
}
```

#### Modificar: `VpnOrchestrator.kt`
```kotlin
// Nueva secuencia para modo socks5+VPN
private suspend fun performSocks5VpnSequence(config: AppConfig) {
    // 1. SSTP connect
    // 2. Proxy auth
    // 3. wstunnel start (socks5 mode)
    // 4. tun2socks start (recibe fd TUN)
    // 5. VpnService establish TUN
    // 6. Todo el tráfico fluye
}
```

### Criterios de éxito Fase 2
- [ ] wstunnel socks5 funciona (verificado en Fase 1)
- [ ] hev-socks5-tunnel binary embebido y ejecuta
- [ ] VpnService establece TUN interface
- [ ] `curl https://httpbin.org/ip` desde el dispositivo retorna IP remota
- [ ] Browser navega por el tunnel
- [ ] DNS resuelve correctamente (no timeout)
- [ ] No hay loop de tráfico (wstunnel se conecta al server)
- [ ] Apps nativas (WhatsApp, etc.) funcionan por el tunnel

---

## Fase 3: UDP forward + WireGuard (stack completo)

### Objetivo
Restaurar el stack completo actual: wstunnel UDP forward → WireGuard → VpnService.

### ⚠️ Corrección: es UDP forward, no TCP
El TL;DR decía "TCP forward" pero es incorrecto. El stack completo usa **UDP forward**:
```
VpnService TUN → WireGuard (127.0.0.1:51820) → wstunnel UDP → WebSocket → Internet
```

### Este ya está implementado
El código actual ya hace esto:
- `WstunnelConfig.kt` línea 54: `"udp://${localPort}:${remoteHost}:${remotePort}?timeout_sec=0"`
- `VpnOrchestrator.kt` línea 534: `vpnService?.startWithWireGuard(appConfig.wgConfig, vpnConfig)`

### Solo necesitamos
1. Verificar que Fase 1 y Fase 2 funcionan
2. El código de Fase 3 ya existe y funciona (asunción a verificar)
3. Cambiar `WstunnelConfig` de vuelta a UDP mode

### Criterios de éxito Fase 3
- [ ] Stack completo funciona: SSTP → Auth → wstunnel → WireGuard → VpnService
- [ ] Todo el tráfico del dispositivo pasa por el tunnel
- [ ] Reconexión automática funciona

---

## Resumen de cambios de código por fase

| Fase | Archivo | Cambio |
|------|---------|--------|
| 1 | `WstunnelConfig.kt` | Agregar `TunnelType` enum, `socks5()` factory, modificar `buildCommand()` |
| 1 | `WstunnelConfig.kt` | Agregar campo `logLevel` y pasarlo a `buildCommand()` |
| 1 | Tests | Actualizar `WstunnelManagerTest.kt` con tests para socks5 |
| 2 | `Tun2SocksManager.kt` | Nuevo archivo (gestión binario hev-socks5-tunnel) |
| 2 | `VpnOrchestrator.kt` | Nueva secuencia `performSocks5VpnSequence()` |
| 2 | `VpnGatewayService.kt` | Nuevo método `startWithSocks5Vpn()` |
| 2 | assets/ | Agregar binario hev-socks5-tunnel ARM64 + config YAML |
| 3 | Ninguno | Ya implementado (UDP forward) |

---

## Riesgos

1. **SSTP connection sharing**: En Fase 1/2, wstunnel necesita TCP para conectarse al server. Si SSTP ya usa el socket, ¿cómo comparten? Respuesta: wstunnel usa su propio socket TCP al server, SSTP es solo para la primera milla.

2. **Proxy HTTP de UCF**: El proxy (`10.14.0.13:3128`) podría no permitir conexiones WebSocket. Necesitamos verificar que el proxy HTTP de UCF hace tunneling correcto de WebSocket upgrade. **A verificar en Fase 1**: si wstunnel conecta o rechaza.

3. **tun2socks performance**: tun2socks añade overhead (lwIP stack). Para testing es suficiente, pero para producción WireGuard es mejor. **Mitigación**: Fase 3 usa WireGuard nativo (mejor performance).

4. **Battery optimization**: Android puede matar el proceso wstunnel en background. Necesita Foreground Service. **Ya implementado**: `VpnGatewayService` es Foreground Service.

5. **hev-socks5-tunnel compatibility**: El binario hev-socks5-tunnel es para Linux. En Android, el fd TUN se pasa diferente. **Mitigación**: Verificar que hev-socks5-tunnel soporta Android (documentación del repo lo menciona).

6. **DNS leak**: Si DNS no pasa por el tunnel, hay leak de privacidad. **Mitigación**: hev-socks5-tunnel tiene proxy DNS integrado (configurado en YAML).

7. **Placeholder IPs**: Las `__VG_IPV4_...__` son placeholders que se resuelven en build-time o runtime. **A verificar**: que se resuelven correctamente.
