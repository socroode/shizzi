# Required for JNI class and native-method lookup after R8 in any embedding app.
-keep class org.webrtc.** { *; }
# Propagate JNI bridge retention to all consuming apps, not just this module.
-keep class org.jni_zero.** { *; }
