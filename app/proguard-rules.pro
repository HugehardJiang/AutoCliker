# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in C:\Users\Hugehard\AppData\Local\Android\Sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the Consumer ProGuard file settings in the Gradle build file.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard/index.html

# Add any project specific ProGuard rules here..

# Keep Compose rules
-keepclassmembers class * extends androidx.compose.ui.node.RootForTest { *; }

# Keep Room entities and DAOs
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep class * { @androidx.room.Dao *; }
-keep class * { @androidx.room.TypeConverter *; }

# Standard Android rules
-keepattributes Signature,Exceptions,SourceFile,LineNumberTable
-dontwarn android.util.Log

# Keep GKD related classes if they use reflection (heuristic)
-keep class cn.idiots.autoclick.data.** { *; }
-keep class cn.idiots.autoclick.util.** { *; }
