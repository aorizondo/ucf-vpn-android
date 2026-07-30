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