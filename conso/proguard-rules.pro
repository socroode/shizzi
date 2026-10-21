# WebRTC JNI loads Java classes using their original names.
# R8 renamed symbols caused SIGABRT during JNI_OnLoad on Android 16.
-keep class org.webrtc.** { *; }
# WebRTC's native JNI_OnLoad also looks up org.jni_zero.JniZero by name.
# It is not referenced in normal Java call sites and R8 otherwise removes it.
-keep class org.jni_zero.** { *; }
