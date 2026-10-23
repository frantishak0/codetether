# WebView: JS-интерфейсов нет, рефлексии нет — специальных правил не требуется.
# Оставляем аннотации, которые использует androidx.security.
-keepattributes *Annotation*
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
