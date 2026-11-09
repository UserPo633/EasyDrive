# Pleos Vehicle SDK: 바인더·리플렉션으로 쓰이는 클래스를 모두 보존
-keep class ai.pleos.** { *; }
-keep interface ai.pleos.** { *; }
-dontwarn ai.pleos.**
# SDK가 내부에서 쓰는 Gson / Kotlin reflect
-keep class com.google.gson.** { *; }
-keep class kotlin.reflect.** { *; }
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
# Android Automotive Car API (플랫폼 공유 라이브러리)
-dontwarn android.car.**
