# R8 rules for Montra.
#
# These apply on top of proguard-android-optimize.txt, and only to the release build type: the debug
# build is unminified on purpose so a stack trace stays readable while developing.
#
# The rule of thumb here is that Play Services and Room both ship consumer rules, so this file is not
# a pile of keeps — it is the small set of things those rules cannot know about, plus the attributes
# a crash report needs to be worth reading.

# Keep the line numbers in crash reports, and drop the original file name. Without these two, every
# stack trace from a release build is `SourceFile` at line 0.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Room generates `*_Impl` classes at compile time and loads them by name; the generated code also
# reflects over the entity constructors. Room's consumer rules cover the common case, this covers the
# case where the database class itself is renamed out from under `Room.databaseBuilder`.
-keep class com.dga.montra.db.** { *; }

# The ad SDKs discover adapters and callbacks reflectively (mediation, UMP consent forms). Their
# consumer rules handle the SDK internals, but not the entry points loaded by name from our manifest
# and meta-data.
-keep class com.google.android.gms.ads.MobileAds { *; }
-keep class com.google.android.ump.** { *; }
-keepclassmembers class * extends android.content.ContentProvider { *; }

# Kotlin coroutines ship rules of their own for the debug agent only; the release path needs no keep.
# This is here so a future `-dontoptimize` experiment has somewhere to sit rather than being pasted in
# a hurry: do not disable optimisation to fix a crash, fix the rule.
