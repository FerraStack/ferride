# R8 rules for release builds.
#
# JNA and the UniFFI bindings (dev.ferride.core) are reached reflectively:
# JNA's direct mapping registers native methods by name, maps Structure
# fields by reflection and calls Callback interfaces (LspListener) from
# native threads. Keep them whole, with the attributes JNA reads.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { *; }
-keep class dev.ferride.core.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
