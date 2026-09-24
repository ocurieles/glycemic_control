# 10 — Despliegue y puesta en marcha

## Requisitos de la máquina de desarrollo
- **JDK ≥ 21** para los emuladores de Firebase (`firebase emulators:*`); Gradle usa su propio JDK 17+ configurado aparte, sin conflicto entre ambos.
- **Android SDK Platform 37 (o la más nueva estable) + Build-Tools a juego**, y **AGP 9+** en `android/gradle/libs.versions.toml`: desde fines de 2026 las versiones "última estable" de Compose/Lifecycle/AndroidX Core exigen `compileSdk ≥ 37` y Android Gradle Plugin ≥ 9.1 (AGP 9 además integra Kotlin y ya no usa el plugin `org.jetbrains.kotlin.android`). Instala la plataforma con `sdkmanager "platforms;android-37.2" "build-tools;37.0.0"` si Android Studio no la trae. **Revisa esto al empezar cada fase**, porque el mínimo sube con el tiempo.

## Firebase (una vez)
1. En console.firebase.google.com, crear el proyecto `checkin-familia` (Analytics opcional; puede desactivarse).
2. **Plan Blaze:** Uso y facturación → Modificar plan → agregar tarjeta.
3. **Alerta de presupuesto:** Google Cloud Console → Facturación → Presupuestos y alertas → USD 1, con alertas al 50%, 90% y 100% a tu correo.
4. Authentication → Método de acceso → **Anónimo**: habilitar.
5. Firestore → Crear base de datos → modo **producción** → ubicación `us-east1`. La ubicación **no se puede cambiar después**.
6. Cloud Messaging: viene activo (API HTTP v1).
7. Configuración del proyecto → Agregar app Android con paquete `com.ingeint.checkin` y el SHA-1 de debug y release (no es obligatorio para FCM, pero sí útil). Descargar `google-services.json` → `android/app/`. **No subirlo a un repo público.**

## Backend
```bash
npm i -g firebase-tools
firebase login
cd firebase
firebase use --add                       # elegir checkin-familia
firebase functions:secrets:set LLU_ENC_KEY   # pegar una cadena aleatoria larga: openssl rand -base64 48
cd functions && npm ci && npm test && cd ..
firebase deploy --only firestore:rules,firestore:indexes,functions
```
- La primera vez, el deploy habilita varias APIs (Cloud Build, Artifact Registry, Cloud Scheduler, Eventarc, Secret Manager). Si falla por eso, esperar un par de minutos y repetir.
- Artifact Registry guarda las imágenes de las funciones. Hay que configurar una **política de limpieza** para no acumular almacenamiento; `firebase deploy` lo ofrece o se hace en la consola.
- Parámetro `LLU_APP_VERSION`: el CLI lo pide en el primer deploy (default `4.16.0`) y se guarda en `functions/.env.<proyecto>`.

## APK
```bash
cd android
./gradlew testDebugUnitTest lint
./gradlew assembleRelease     # requiere keystore configurado (F8)
```
- **Keystore:** generarlo una sola vez y guardarlo con respaldo **fuera del repo**. Si se pierde, no se puede actualizar la app instalada sin desinstalarla.
- Distribución: copiar el APK a cada teléfono (WhatsApp, Drive o cable) → permitir "Instalar apps desconocidas" para esa fuente → instalar.
- Actualizaciones: instalar el nuevo APK encima, firmado con el mismo keystore y un `versionCode` mayor.

## Checklist del teléfono de Cesar
- [ ] Instalar el APK y abrir → "Soy Cesar" → código del padre.
- [ ] Permitir notificaciones.
- [ ] Batería: "Sin restricciones" y seguir la guía del fabricante que muestra la app (inicio automático, bloquear en recientes si aplica).
- [ ] Alarmas exactas: verificar en diagnóstico (con `USE_EXACT_ALARM` debería estar concedido).
- [ ] Ubicación: "Permitir mientras se usa la app" (para el SOS).
- [ ] SMS, si se activa el SOS sin conexión.
- [ ] **Fecha y hora automáticas** activadas.
- [ ] Probar cada vibración en la pantalla de práctica **con Cesar**.
- [ ] Con el teléfono en silencio, en el bolsillo: esperar un recordatorio real.
- [ ] Verificar que LibreLink sigue funcionando normalmente y sin cambios.

## Checklist de cada teléfono de padre
- [ ] Instalar → "Papá/Mamá" → crear familia (el primero) o unirse con código (el segundo).
- [ ] Permitir notificaciones y **acceso a No Molestar** (para el SOS).
- [ ] Permitir notificaciones a pantalla completa (Android 14+: Ajustes → Apps → Check-in).
- [ ] Batería sin restricciones.
- [ ] Configurar el horario escolar real, el intervalo y los umbrales.
- [ ] Conectar LibreLinkUp con la **cuenta de seguidor dedicada** → "Probar lectura".
- [ ] Configurar los números para el SMS de SOS (opcional).
- [ ] Prueba completa: revisión, mensaje, SOS y "Voy en camino".

## Mantenimiento
- Si los pushes llegan sin valor y Ajustes muestra "La integración necesita actualización", hay que subir `LLU_APP_VERSION` a la versión actual de LibreLinkUp en Play Store y hacer `firebase deploy --only functions`.
- Revisar mensualmente la facturación (debería estar en USD 0).
- Rotar `LLU_ENC_KEY` implica volver a conectar LibreLinkUp.
