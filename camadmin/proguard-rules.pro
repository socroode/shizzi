# Native WebRTC JNI requires original Java symbols in the viewer too.
-keep class org.webrtc.** { *; }
# Keep the Chromium jni_zero bridge required by WebRTC's native library.
-keep class org.jni_zero.** { *; }
