# WebRTC JNI loads Java classes using their original names.
# R8 renamed symbols caused SIGABRT during JNI_OnLoad on Android 16.
-keep class org.webrtc.** { *; }
