# Native WebRTC JNI requires original Java symbols in the viewer too.
-keep class org.webrtc.** { *; }
# Keep the Chromium jni_zero bridge required by WebRTC's native library.
-keep class org.jni_zero.** { *; }
# JniZero in this WebRTC AAR references an absent generated Java wrapper.
# The native shared library requires JniZero itself, not this generated wrapper.
# Restrict suppression to the one missing synthetic helper; never ignore all R8 errors.
-dontwarn org.jni_zero.JniZeroJni
