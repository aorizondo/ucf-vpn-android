# UCF VPN — estado del trabajo (actualizado 2026-09-06)

Auditoría y corrección del plan `.omo/plans/ucf-vpn-split-tunnel.md`, que declaraba
las Fases 1–6 completadas. **Ninguna funcionaba: el árbol ni siquiera compilaba.**

- **Rama**: `fix/ci-baseline-and-audit` (8 commits, pusheada, **sin PR abierto**)
- **Base**: `main` @ `89c9ff5`
- **Restricción**: compilar y testear **sólo en GitHub Actions, nunca en local**

---

## Estado del CI

**Verde completo** — run `34059786038`, commit `cd076cf`.

| Job | Estado |
|-----|--------|
| Compile wstunnel (arm64 + armv7 + x86_64) | ✅ |
| Compile hev-socks5-tunnel (arm64 + armv7 + x86_64) | ✅ |
| Build APK (compila + 260 tests JVM) | ✅ |
| Instrumented tests (emulador API 29, 39 tests) | ✅ |

Progresión: el árbol **no compilaba** → 15 fallos → 2 → 1 → **0**, y los
instrumentados de no poder dexearse a 39 en verde.

El CI compila también x86_64, que es la ABI del emulador. No es para teléfonos:
sin ella los tests del binding JNI no encontraban librería y se saltaban en
silencio, dejando verde una suite que no probaba la pieza central.

## Estado de los tests (2026-09-06)

Los 2 tests instrumentados que quedaban se corrigieron con la causa real del
reporte: `logScreen_showsEmptyState` esperaba un log vacío que la app nunca puede
mostrar (el orquestador registra el estado inicial desde su `init`), y
`connectButton` fallaba porque el diálogo de consentimiento de VPN tapa la
Activity y no deja jerarquía Compose que inspeccionar.

## Histórico: lo que quedaba en rojo el 2026-09-05

Dos tests de `MainActivityTest`. **No pude descargar los reportes** (la descarga del
artifact falló por timeout TLS dos veces), así que estas causas son hipótesis a
confirmar leyendo el reporte antes de tocar nada:

1. **`logScreen_showsEmptyState`** — espera «No log entries yet», pero el
   `VpnOrchestrator` emite `"State: Disconnected"` desde su bloque `init` al
   construirse, así que la pantalla de logs probablemente nunca está vacía.
   Si se confirma, el test es el equivocado, no el código.
2. **`connectButton_isClickable`** — pulsar Connect ahora lanza el diálogo de
   consentimiento VPN del sistema, que queda **por encima** de la Activity; la
   comprobación posterior de «VPN Status» falla porque la app está tapada. Habría
   que cerrar el diálogo o no asertar nada después del tap.

Los otros tres (`statusScreen`, `configScreen`, `navigation`) **ya pasan**.

Para verlos:
```bash
gh run download <RUN_ID> -n instrumented-test-reports -D /tmp/x
# los XML están en outputs/androidTest-results/connected/debug/
```

---

## Bloqueadores del plan: estado

| # | Bloqueador | Estado |
|---|-----------|--------|
| P0.1 | `VpnGatewayService` nunca se instanciaba | ✅ cableado (consent + startForegroundService + LocalBinder) |
| P0.2 | Binarios inejecutables (SELinux prohíbe exec desde `filesDir`) | ✅ van en `jniLibs/<abi>/lib*.so`, se ejecutan desde `nativeLibraryDir` |
| P0.3 | El binario de hev-socks5-tunnel no existía | ✅ job de CI que lo compila (tag 2.17.1, módulo `hev-socks5-tunnel-bin`) |
| P0.4 | No hay data path SSTP | ✅ implementado 2026-09-06 (decisión tomada, ver abajo) |
| P0.5 | Faltaba `FOREGROUND_SERVICE` | ✅ añadido (+ `POST_NOTIFICATIONS`) |

P1 (deadlock del ReconnectManager, NPE al construir, cleanup autocancelado, loop de
tráfico, `protect()` sin `bind()`, TUN huérfano, verificaciones tautológicas,
reconexión imposible desde `VpnRunning`, reintento infinito, login que aceptaba
contraseñas incorrectas, config descartada): **todos corregidos**.

P2 (4 bugs de RFC en PPP: padding de PAP, Auth-Protocol mal dirigido, Ack ciego a
MS-CHAPv2, IPCP sin responder; + gateway y Hash Protocol Bitmask): **corregidos**.

P3 (código muerto): **~500 líneas eliminadas** (`state/VpnOrchestrator.kt`,
`VpnLogger`, `LogRepository`, `PPPHandler`). Queda WireGuard por retirar.

---

## P0.4 — resuelto, con un riesgo abierto

**Respuesta del usuario (2026-09-06)**: el proxy `10.14.0.13:3128` es alcanzable
desde cualquier punto de acceso de la red UCF, sin SSTP. Lo que el split resuelve
es **poder abrir sitios web de la red interna** (por ejemplo el portal cautivo del
proxy) mientras la VPN está levantada.

Eso descarta renunciar al split: sin enrutado real el tráfico interno sale por el
proxy de Internet y esos sitios quedan inalcanzables. Implementado:

- `sstp/data/SplitRouter.kt` — decide por paquete SSTP vs SOCKS5 (sin APIs de
  Android, testeable en JVM).
- `sstp/data/SstpDataPath.kt` — bucles de reenvío en ambos sentidos.
- **Socketpair en lugar de JNI**: un `VpnService` tiene un único TUN que no se
  puede compartir, así que a hev-socks5-tunnel se le entrega un extremo de un
  socketpair `AF_UNIX/SOCK_SEQPACKET`. Lee y escribe paquetes IP igual que en un
  TUN y SEQPACKET conserva los límites de paquete. Evita el JNI que el plan daba
  por necesario.
- PPP: los frames `0x0021` se separan antes de parsear y se entregan al data path.

### Resuelto: el subproceso NO hereda descriptores

Medido en el emulador (API 29, 2026-09-06): el hijo reportó `NO`. Android cierra
los descriptores por encima de stderr al hacer `exec`, igual que
`closeDescriptors()` en OpenJDK.

Eso **invalidaba el diseño original**, no sólo el socketpair: a hev se le pasaba
un número de descriptor por línea de comandos (`-f <fd>`) que en el hijo no
apuntaba a nada. El túnel no habría transportado un solo paquete, y nada lo
delató porque el código nunca se había ejecutado.

**Solución**: hev ya trae su capa JNI (`src/hev-jni.c`, bajo `#ifdef ANDROID`),
así que no hubo que escribir C. `TProxyService.kt` declara los cuatro nativos y
el CI compila el módulo de librería compartida con `PKGNAME`/`CLSNAME` apuntando
a esa clase. El socketpair sobrevive: sólo cambió cómo arranca la librería, no
cómo se demultiplexa. wstunnel sigue como subproceso porque abre su propio
socket y no necesita heredar nada.

`TProxyServiceInstrumentedTest` verifica el binding en cada run.

### Comportamiento de hev que conviene recordar

`TProxyStartService` devuelve `true` con un config inexistente: la librería no
valida la ruta ni informa. Un config malo habría dado un túnel "running" incapaz
de transportar tráfico —en el móvil, una VPN conectada sin internet y sin nada en
el log—. `Tun2SocksManager` comprueba el fichero antes de entregárselo.

## Hallazgos que conviene no perder

**Todo lo que se ejecutó por primera vez reveló un defecto de meses.** Seis fallos
de test eran preexistentes:

- `PppHandlerTest`: los valores de FCS-16 esperados (`0x84b0`, `0xe5f6`) estaban
  **inventados**. Verifiqué el CRC de forma independiente: la implementación es
  correcta (cumple GOODFCS `0xf0b8` de RFC 1662) y los valores reales son `0x0f87`
  y `0xd5a8`.
- `SstpProtocolTest`: el helper de hex usaba `%02X` contra valores de Python en
  minúsculas.
- `WstunnelManagerTest`: esperaba 15 argumentos donde se generan 13, contradiciendo
  al test vecino.
- `ProxyAuthServiceTest`: `FormBody` de OkHttp codifica los espacios como `%20`, no
  como `+`.
- `SstpPacket.length` era parámetro del constructor que `pack()` ignoraba pero
  `unpack()` rellenaba: un roundtrip nunca podía preservarlo (**defecto real**).
- **Bug real de UI**: `StatusScreen` no tenía scroll y el botón Connect quedaba
  fuera de pantalla en pantallas cortas — el botón principal de la app era
  inalcanzable. Corregido.

El compilador **no** se quejó de `scope` usado antes de declararse, lo que confirma
que ese defecto era un NPE en runtime al construir el orquestador (coherente con el
crash del commit `544546a`), no un error de compilación.

---

## Trabajo pendiente

1. Retirar WireGuard del árbol (`WireGuardManager` declara CONNECTED sin hacer
   handshake; la dependencia `com.wireguard.android:tunnel` tiene **cero imports**;
   `wg/` completo está huérfano).
2. Recuperar los tests de `app/test-broken/` (`VpnOrchestratorTest.kt` 20 KB,
   `VpnIntegrationTest.kt` 27 KB), tracked en git pero fuera de todo source set.
   Ya escribí un `VpnOrchestratorTest` nuevo, pero cubre menos.
3. Abrir el PR.
4. E2E en dispositivo (Fase 7, `docs/E2E-TESTING.md`).

**No cubierto a propósito**: las fases PPP (LCP→AUTH→IPCP). Que una transición sea
aceptada depende de una carrera real entre los eventos del túnel y el punto en que
el orquestador llega a `PppNegotiating`; un test así sería intermitente, que es peor
que no tenerlo. Documentado en `VpnOrchestratorTest.kt`. Cubrirlo bien exige que el
orquestador deje de hacer polling y observe los eventos — cambio de producción.

---

## Comandos útiles

```bash
git checkout fix/ci-baseline-and-audit
gh workflow run build.yml --ref fix/ci-baseline-and-audit   # lanzar CI (~17 min)
gh run list --branch fix/ci-baseline-and-audit --limit 3
gh run view <RUN_ID> --json conclusion,jobs --jq '.jobs[] | "\(.name) :: \(.conclusion) :: \(.databaseId)"'
gh run view --job <JOB_ID> --log | grep -oE "e: file://\S+ .*$"   # errores de compilación
gh run download <RUN_ID> -n unit-test-reports -D /tmp/x           # reportes JVM
gh run download <RUN_ID> -n instrumented-test-reports -D /tmp/y   # reportes emulador
```

`ci-reports/` contiene los reportes y logs del último run, por si GitHub los expira.
