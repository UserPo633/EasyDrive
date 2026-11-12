# 클래스·메서드 이름을 바꾸지 않는다. Pleos SDK가 호출 스택과 kotlin-reflect로 권한을 확인하므로
# 이름이 바뀌면 실행 중에만 드러나는 오류가 날 수 있다(사용하지 않는 코드 제거만 적용).
-dontobfuscate
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
