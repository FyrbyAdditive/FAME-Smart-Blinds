# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep Gson type tokens
-keepattributes Signature
-keepattributes *Annotation*

# Keep data classes for Gson
-keep class com.fyrbyadditive.famesmartblinds.data.model.** { *; }
-keep class com.fyrbyadditive.famesmartblinds.data.remote.** { *; }
