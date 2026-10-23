# Required for JNI class and native-method lookup after R8 in any embedding app.
-keep class org.webrtc.** { *; }
# Propagate JNI bridge retention to all consuming apps, not just this module.
-keep class org.jni_zero.** { *; }
# JniZero in this WebRTC AAR references an absent generated Java wrapper.
# The native shared library requires JniZero itself, not this generated wrapper.
# Restrict suppression to the one missing synthetic helper; never ignore all R8 errors.
-dontwarn org.jni_zero.JniZeroJni
