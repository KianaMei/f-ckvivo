# The test build is minified but still gets read from LSPosed/logcat stack traces.
# Keep real file names and line numbers instead of r8-map-id/Unknown Source.
# proguard-release.pro does not list these, so the release APK stays stripped.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
