# Desktop host-rule scan

`HostScanCli` compiles with the production `HostScanner`, `HostRuleSet` and related classes. It treats the supplied APK as ZIP/DEX data and never executes its code. The TSV report contains each rule's `UNIQUE`, `NOT_FOUND` or `AMBIGUOUS` status and all candidates. `--require-unique` exits with status 2 when any rule is not unique; a successful uniqueness scan alone does not validate a feature's undeclared reflection, UI or business semantics.

Requirements: JDK 21, `com.android.tools.smali:smali-dexlib2:3.0.9`, Guava, and an Android SDK `android.jar` (for the production cache type). The command-line `scan` path does not instantiate the Android cache. Dependencies may be taken from Gradle caches; this tool does not download them.

```powershell
python tools/host-scan/run.py C:/path/host.apk app/src/main/assets/douyin-host-rules-41.2.0.tsv work/412-scan.tsv --java-home C:/jdk-21.0.9+10 --dexlib C:/path/smali-dexlib2-3.0.9.jar --guava C:/path/guava.jar --android-jar C:/path/Android/Sdk/platforms/android-37.0/android.jar --require-unique
```

`DumpContracts.java` is an optional bounded instruction-reference exporter for named class/method pairs. It does not decompile the entire APK or run host code.
