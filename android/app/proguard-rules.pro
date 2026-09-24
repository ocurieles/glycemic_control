# Reglas de R8 (docs/08 F8). Firebase/Room/WorkManager ya traen sus propias reglas de
# consumidor (consumer-rules.pro dentro de cada aar), así que esto es una capa extra de
# seguridad para las clases propias que se leen/escriben por nombre de campo (Firestore
# con Maps, no con toObject<T>() — no se usa en este proyecto, pero por si acaso) o que
# Room referencia por reflection en tiempo de ejecución.
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses

-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class *
-keep class com.ingeint.checkin.data.local.** { *; }

# Contratos de datos propios (docs/03, docs/04): settings/eventos que se serializan a
# mano hacia/desde Firestore.
-keep class com.ingeint.checkin.data.model.** { *; }
