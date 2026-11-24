# JNI entry points
-keep class org.hdricapture.processing.NativeBridge { *; }
-keepclasseswithmembernames class * { native <methods>; }
# Called from native code by name.
-keep interface org.hdricapture.processing.NativeBridge$Progress { *; }
