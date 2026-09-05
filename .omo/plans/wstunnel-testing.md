# wstunnel-testing - Work Plan

## TL;DR (For humans)

**Qué se hace**: Testing progresivo de wstunnel en Android para validar su funcionamiento antes del refactor a Expo React Native. Se prueba en 3 fases: (1) socks5 manual en browser/app, (2) socks5 + VpnService con tun2socks para tráfico completo, (3) UDP forward + WireGuard (stack completo ya implementado).

**Por qué este enfoque**: tproxy no funciona en Android sin root (requiere CAP_NET_ADMIN). socks5 es la única opción viable para testing sin privilegios. El enfoque progresivo permite validar cada capa antes de agregar complejidad.

**Qué NO hace**: No implementa el refactor a Expo RN. No modifica la UI existente. No agrega funcionalidades nuevas más allá de lo necesario para testing.

**Esfuerzo**: Fase 1 (1 día), Fase 2 (3-5 días), Fase 3 (verificación). Total: 4-7 días.

**Riesgos principales**: Proxy HTTP de UCF podría bloquear WebSocket. tun2socks podría tener problemas de compatibilidad con Android. DNS leak si hev-socks5-tunnel no maneja DNS correctamente.

**Decisiones clave**: hev-socks5-tunnel (no xjasonlyu/tun2socks) para Phase 2. TunnelType enum en WstunnelConfig. Proxy auth sin credenciales (matchea modo UDP actual).

---

## Scope

### In scope
- Agregar `TunnelType` enum (UDP/SOCKS5/HTTP/TCP) a `WstunnelConfig.kt`
- Modificar `buildCommand()` para soportar socks5, HTTP, y TCP forward
- Agregar factory method `socks5()` a `WstunnelConfig`
- Testing manual de wstunnel socks5 en Termux
- Integración de `hev-socks5-tunnel` para Phase 2
- `Tun2SocksManager.kt` para gestionar hev-socks5-tunnel
- `VpnGatewayService.startWithSocks5Vpn()` para modo socks5+VPN
- `VpnOrchestrator.performSocks5VpnSequence()` para nueva secuencia
- Config YAML de hev-socks5-tunnel en assets/
- Tests unitarios para nuevo TunnelType y socks5 factory

### Out of scope
- Refactor a Expo React Native (otro plan)
- UI changes (mantener Jetpack Compose existente)
- Split tunneling
- Kill switch
- Auto-start on boot
- Múltiples perfiles

---

## Verification strategy

**Test framework**: JUnit 4 + Mockito (existente en el proyecto)

**Agent-executed QA por todo**:
- Cada todo tiene happy path + failure path
- Evidencia: logs de wstunnel, curl output, browser navigation
- Commands específicos para verificar

**Final verification wave**: F1-F4 en paralelo después de todos los todos

---

## Execution strategy

**Waves**: 3 waves secuenciales (Fase 1 → Fase 2 → Fase 3)

**Dependencies**: 
- Wave 1 (Fase 1): Independiente, puede empezar inmediatamente
- Wave 2 (Fase 2): Depende de Wave 1 exitoso
- Wave 3 (Fase 3): Depende de Wave 2 exitoso

**Parallel execution**: Dentro de cada wave, los todos son secuenciales (dependen entre sí)

---

## Todos

### Wave 1: Fase 1 - wstunnel socks5 (testing manual)

- [ ] 1. **WstunnelConfig.kt: Agregar TunnelType enum con UDP/SOCKS5/HTTP/TCP** - Agregar enum class TunnelType al inicio del archivo, antes del data class. Valores: UDP (default), SOCKS5, HTTP, TCP. Referencia: `app/src/main/java/com/ucfvpn/app/wstunnel/WstunnelConfig.kt` línea 6.
  - **Acceptance**: TunnelType enum existe con 4 valores, UDP es default
  - **Happy QA**: Compila sin errores, WstunnelConfig() default usa TunnelType.UDP
  - **Failure QA**: Si enum falta, build falla con "Unresolved reference: TunnelType"
  - **Commit**: `feat(wstunnel): add TunnelType enum for multi-mode support`

- [ ] 2. **WstunnelConfig.kt: Agregar campo logLevel y factory method socks5()** - Agregar `val logLevel: String = "INFO"` al data class. Agregar companion object con `fun socks5(localPort: Int = 1080, serverUrl: String, proxyHost: String, proxyPort: Int, logLevel: String): WstunnelConfig`. Referencia: `app/src/main/java/com/ucfvpn/app/wstunnel/WstunnelConfig.kt` líneas 10-37, 70-76.
  - **Acceptance**: WstunnelConfig.socks5() retorna config con tunnelType=SOCKS5, logLevel configurable
  - **Happy QA**: `WstunnelConfig.socks5(localPort=1080, serverUrl="wss://test").tunnelType == TunnelType.SOCKS5`
  - **Failure QA**: Si factory falta, test falla con "Unresolved reference: socks5"
  - **Commit**: `feat(wstunnel): add logLevel field and socks5 factory method`

- [ ] 3. **WstunnelConfig.kt: Modificar buildCommand() para soportar todos los TunnelType** - Reemplazar buildCommand() actual con switch sobre tunnelType. UDP usa lógica existente. SOCKS5: `socks5://0.0.0.0:${localPort}`. HTTP: `http://0.0.0.0:${localPort}`. TCP: `tcp://${localPort}:${remoteHost}:${remotePort}`. Agregar `--log-lvl ${logLevel}` al final del comando. Referencia: `app/src/main/java/com/ucfvpn/app/wstunnel/WstunnelConfig.kt` líneas 51-68.
  - **Acceptance**: buildCommand() retorna args correctos para cada TunnelType
  - **Happy QA**: `WstunnelConfig(tunnelType=SOCKS5, localPort=1080).buildCommand("/bin/wstunnel")` contiene `-L socks5://0.0.0.0:1080`
  - **Failure QA**: Si TCP con DYNAMIC mode, lanza IllegalArgumentException
  - **Commit**: `feat(wstunnel): support SOCKS5/HTTP/TCP tunnel types in buildCommand()`

- [ ] 4. **WstunnelManagerTest.kt: Agregar tests para TunnelType y socks5 factory** - Agregar tests: testTunnelTypeDefaultIsUDP, testSocks5Factory, testBuildCommandSocks5, testBuildCommandTCP, testBuildCommandTCPDynamicThrows. Referencia: `app/src/test/java/com/ucfvpn/app/wstunnel/WstunnelManagerTest.kt`.
  - **Acceptance**: Todos los tests pasan, cobertura de TunnelType al 100%
  - **Happy QA**: `./gradlew test --tests "com.ucfvpn.app.wstunnel.WstunnelManagerTest"` pasa
  - **Failure QA**: Si test falla, revisar implementación de TunnelType o factory
  - **Commit**: `test(wstunnel): add tests for TunnelType and socks5 factory`

- [ ] 5. **VpnOrchestrator.kt: Agregar debugWstunnelSocks5 flag a AppConfig y connect()** - Agregar `val debugWstunnelSocks5: Boolean = false` a AppConfig (línea 53). En connect() (línea 275), usar WstunnelConfig.socks5() cuando flag es true. Referencia: `app/src/main/java/com/ucfvpn/app/orchestrator/VpnOrchestrator.kt` líneas 45-54, 254-286.
  - **Acceptance**: AppConfig tiene debugWstunnelSocks5, connect() usa socks5 cuando flag=true
  - **Happy QA**: Con flag=true, wstunnelConfig.tunnelType == TunnelType.SOCKS5
  - **Failure QA**: Si flag no se propaga, wstunnel usa UDP mode
  - **Commit**: `feat(orchestrator): add debugWstunnelSocks5 flag for testing`

- [ ] 6. **Testing manual: Verificar wstunnel socks5 en Android** - Ejecutar en Termux: `wstunnel client -L socks5://0.0.0.0:1080 -p http://PROXY:3128 wss://SERVER --websocket-ping-frequency 10s --log-lvl DEBUG`. Verificar con `curl -x socks5h://127.0.0.1:1080 https://httpbin.org/ip`. Configurar proxy socks5 en browser y navegar. Referencia: `scripts/download_wstunnel.sh` línea 21 (v10.5.1).
  - **Acceptance**: wstunnel inicia, curl retorna IP remota, browser navega por proxy
  - **Happy QA**: `curl -x socks5h://127.0.0.1:1080 https://httpbin.org/ip` retorna IP del servidor wstunnel
  - **Failure QA**: Si error 407, agregar `--http-proxy-login` y `--http-proxy-password`
  - **Commit**: N/A (testing manual, no código)

### Wave 2: Fase 2 - wstunnel socks5 + VpnService (tun2socks)

- [ ] 7. **Descargar hev-socks5-tunnel ARM64 binary y agregar a assets/** - Descargar de https://github.com/heiher/hev-socks5-tunnel/releases. Renombrar a `hev_socks5_tunnel_arm64`. Copiar a `app/src/main/assets/`. Verificar que es ELF ARM64. Referencia: `scripts/download_wstunnel.sh` (patrón de descarga).
  - **Acceptance**: `app/src/main/assets/hev_socks5_tunnel_arm64` existe, es ELF ARM64
  - **Happy QA**: `file app/src/main/assets/hev_socks5_tunnel_arm64` muestra "ELF 64-bit LSB executable, ARM aarch64"
  - **Failure QA**: Si no es ARM64, verificar URL de descarga o arquitectura
  - **Commit**: `chore(assets): add hev-socks5-tunnel ARM64 binary`

- [ ] 8. **Crear hev_socks5_tunnel.yaml en assets/ con config para Android** - Crear `app/src/main/assets/hev_socks5_tunnel.yaml` con tunnel.device: auto, tunnel.stack: lwip, tunnel.dns: address 127.0.0.1:5353, name 8.8.8.8, proxy.server: 127.0.0.1:1080. Referencia: hev-socks5-tunnel documentation.
  - **Acceptance**: YAML válido, device auto, DNS config correcto, proxy apunta a wstunnel
  - **Happy QA**: YAML parsea sin errores, todos los campos requeridos presentes
  - **Failure QA**: Si YAML inválido, hev-socks5-tunnel falla al iniciar
  - **Commit**: `feat(assets): add hev-socks5-tunnel config for Android`

- [ ] 9. **Tun2SocksManager.kt: Crear manager para hev-socks5-tunnel** - Crear `app/src/main/java/com/ucfvpn/app/tun2socks/Tun2SocksManager.kt`. Similar a WstunnelManager: extractBinary(), start(config), stop(), isRunning(). Recibe fd TUN desde VpnService via constructor o método. Proceso: `hev-socks5-tunnel -c /path/to/config.yaml`. Referencia: `app/src/main/java/com/ucfvpn/app/wstunnel/WstunnelManager.kt` (patrón a seguir).
  - **Acceptance**: Tun2SocksManager extrae binary, inicia proceso, maneja estado
  - **Happy QA**: `Tun2SocksManager(context).start(tunFd)` retorna Result.success
  - **Failure QA**: Si binary no se extrae, falla con "Binary extraction failed"
  - **Commit**: `feat(tun2socks): add Tun2SocksManager for hev-socks5-tunnel`

- [ ] 10. **Tun2SocksManagerTest.kt: Tests para Tun2SocksManager** - Agregar tests: testExtractBinary, testStartStopsProcess, testIsRunningState, testStopCleanup. Referencia: `app/src/test/java/com/ucfvpn/app/wstunnel/WstunnelManagerTest.kt` (patrón).
  - **Acceptance**: Todos los tests pasan, error handling verificado
  - **Happy QA**: `./gradlew test --tests "com.ucfvpn.app.tun2socks.Tun2SocksManagerTest"` pasa
  - **Failure QA**: Si test falla, revisar ProcessBuilder o manejo de errores
  - **Commit**: `test(tun2socks): add unit tests for Tun2SocksManager`

- [ ] 11. **VpnGatewayService.kt: Agregar startWithSocks5Vpn() method** - Agregar método que: (1) establece TUN interface via Builder, (2) pasa fd TUN a Tun2SocksManager, (3) inicia tun2socks, (4) retorna Result. Usar Builder.addRoute() con rangos CIDR para excluir IPs de infraestructura (proxy, wstunnel server). Referencia: `app/src/main/java/com/ucfvpn/app/vpn/VpnGatewayService.kt` (métodos existentes establishTunInterface, protectSocket).
  - **Acceptance**: startWithSocks5Vpn() establece TUN, inicia tun2socks, no hay loop de tráfico
  - **Happy QA**: `vpnService.startWithSocks5Vpn(wstunnelConfig)` retorna Result.success
  - **Failure QA**: Si loop de tráfico, wstunnel no se conecta al server
  - **Commit**: `feat(vpn): add startWithSocks5Vpn for socks5+VPN mode`

- [ ] 12. **VpnOrchestrator.kt: Agregar performSocks5VpnSequence() method** - Crear nueva secuencia: (1) SSTP connect, (2) Proxy auth, (3) wstunnel start socks5, (4) tun2socks start, (5) VpnService establish TUN, (6) verify connectivity. Referencia: `app/src/main/java/com/ucfvpn/app/orchestrator/VpnOrchestrator.kt` (performConnectionSequence como patrón).
  - **Acceptance**: performSocks5VpnSequence() ejecuta secuencia completa sin errores
  - **Happy QA**: Con debugWstunnelSocks5=true, secuencia completa funciona
  - **Failure QA**: Si tun2socks falla, retorna error y limpia recursos
  - **Commit**: `feat(orchestrator): add performSocks5VpnSequence for socks5+VPN`

- [ ] 13. **Testing integration: Verificar socks5+VPN en Android real** - Build APK con debugWstunnelSocks5=true. Instalar en dispositivo Android. Verificar: (1) wstunnel socks5 inicia, (2) tun2socks inicia, (3) VpnService establece TUN, (4) `curl https://httpbin.org/ip` retorna IP remota, (5) browser navega, (6) DNS resuelve, (7) apps nativas funcionan. Referencia: `app/build.gradle` (assembleDebug).
  - **Acceptance**: Todo el tráfico pasa por tunnel, DNS funciona, no hay leak
  - **Happy QA**: `curl https://httpbin.org/ip` desde dispositivo retorna IP remota
  - **Failure QA**: Si DNS falla, verificar config YAML de hev-socks5-tunnel
  - **Commit**: N/A (testing integration)

### Wave 3: Fase 3 - UDP forward + WireGuard (verificación)

- [ ] 14. **Verificar stack completo UDP forward + WireGuard funciona** - Cambiar debugWstunnelSocks5=false. Ejecutar conexión completa. Verificar: (1) SSTP conecta, (2) proxy auth funciona, (3) wstunnel UDP inicia, (4) WireGuard conecta, (5) VpnService establece TUN, (6) todo el tráfico pasa por tunnel. Referencia: `app/src/main/java/com/ucfvpn/app/orchestrator/VpnOrchestrator.kt` (performConnectionSequence).
  - **Acceptance**: Stack completo funciona, no hay regresiones
  - **Happy QA**: `curl https://httpbin.org/ip` desde dispositivo retorna IP remota
  - **Failure QA**: Si WireGuard falla, verificar endpoint y claves
  - **Commit**: N/A (verificación)

---

## Final verification wave

- [ ] F1. **Plan compliance audit** - Verificar que todos los todos del plan fueron implementados. Cada todo tiene acceptance criteria, happy QA, failure QA, y commit message. Referencia: este archivo.
  - **Acceptance**: Todos los todos completados, sin pendientes
  - **Happy QA**: Lista de todos vs implementación coincide 100%
  - **Failure QA**: Si falta algún todo, identificar y completar
  - **Commit**: N/A

- [ ] F2. **Code quality review** - Revisar código nuevo: TunnelType enum, socks5 factory, buildCommand(), Tun2SocksManager, VpnGatewayService.startWithSocks5Vpn(), VpnOrchestrator.performSocks5VpnSequence(). Verificar: naming conventions, error handling, logging, null safety. Referencia: `app/src/main/java/com/ucfvpn/app/` (estructura existente).
  - **Acceptance**: Código cumple estándales del proyecto, no hay code smells
  - **Happy QA**: No hay warnings en Android Studio, lint pasa
  - **Failure QA**: Si hay issues, corregir antes de handoff
  - **Commit**: `refactor: code quality improvements for wstunnel testing`

- [ ] F3. **Real manual QA** - Ejecutar en dispositivo Android real: (1) Phase 1 socks5 manual, (2) Phase 2 socks5+VPN, (3) Phase 3 UDP+WireGuard. Verificar: conectividad, DNS, performance, estabilidad, battery usage. Referencia: todos los criterios de éxito de cada fase.
  - **Acceptance**: Las 3 fases funcionan en dispositivo real
  - **Happy QA**: Navegación fluida, DNS responde, no hay drops de conexión
  - **Failure QA**: Si hay issues, documentar y crear bug fixes
  - **Commit**: N/A (QA manual)

- [ ] F4. **Scope fidelity** - Verificar que no se implementó nada fuera de scope: no hay UI changes, no hay Expo RN, no hay split tunneling, no hay kill switch. Referencia: Scope section de este plan.
  - **Acceptance**: Solo se implementó lo definido en el plan
  - **Happy QA**: `git diff --stat` muestra solo archivos relevantes
  - **Failure QA**: Si hay cambios fuera de scope, revertir
  - **Commit**: N/A

---

## Commit strategy

**Commits por todo**: Cada todo tiene su propio commit message (ver Acceptance criteria).

**Branch strategy**: 
- Crear branch `feat/wstunnel-socks5-testing` desde main
- Commits incrementales por cada todo
- Merge a main después de F4 exitoso

**Commit message format**: `type(scope): description` (conventional commits)

---

## Success criteria

1. **Phase 1**: wstunnel socks5 funciona en Android, browser puede navegar por proxy
2. **Phase 2**: socks5+VPN funciona, todo el tráfico pasa por tunnel, DNS resuelve
3. **Phase 3**: UDP+WireGuard funciona, no hay regresiones
4. **Code quality**: Sin warnings, lint pasa, tests cubren nuevo código
5. **No regressions**: Funcionalidad existente no se rompió

---

## Risk mitigation

1. **Proxy WebSocket**: Si proxy bloquea WebSocket, probar con `--tls-sni-override=google.com`
2. **hev-socks5-tunnel compatibility**: Si no funciona en Android, considerar xjasonlyu/tun2socks como fallback
3. **DNS leak**: Monitorear con `tcpdump` para verificar que DNS pasa por tunnel
4. **Performance**: tun2socks añade overhead, medir con speedtest.net

---

## References

- `app/src/main/java/com/ucfvpn/app/wstunnel/WstunnelConfig.kt` - Config actual a modificar
- `app/src/main/java/com/ucfvpn/app/wstunnel/WstunnelManager.kt` - Manager a extender
- `app/src/main/java/com/ucfvpn/app/orchestrator/VpnOrchestrator.kt` - Orchestrator a modificar
- `app/src/main/java/com/ucfvpn/app/vpn/VpnGatewayService.kt` - VPN service a extender
- `app/src/test/java/com/ucfvpn/app/wstunnel/WstunnelManagerTest.kt` - Tests a agregar
- `scripts/download_wstunnel.sh` - Patrón de descarga de binarios
- `https://github.com/erebe/wstunnel` - Documentación wstunnel
- `https://github.com/heiher/hev-socks5-tunnel` - Documentación hev-socks5-tunnel
- `.omo/plans/wstunnel-testing-progressive.md` - Plan detallado con análisis de Momus
