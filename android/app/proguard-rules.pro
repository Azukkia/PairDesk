# PairDesk release rules (minification is disabled for now, see build.gradle.kts).
# WebRTC uses JNI and reflection on org.webrtc classes.
-keep class org.webrtc.** { *; }
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-dontwarn org.eclipse.paho.client.mqttv3.**
