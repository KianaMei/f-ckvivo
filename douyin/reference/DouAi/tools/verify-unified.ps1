param(
    [Parameter(Mandatory=$true)][string]$HostApk,
    [switch]$RunLint
)
$ErrorActionPreference = 'Stop'
$env:DOUAI_TEST_APK = (Resolve-Path -LiteralPath $HostApk).Path
$projectDirectory = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectDirectory
try {
    $testPatterns = @(
        '*412*', '*Unified*', '*WebDav*', '*Portable*', '*PcmMp3EncoderTest',
        '*HostStorageNamespaceTest', '*FeedAdFilterHookTest', '*FeedFilterPolicyTest',
        '*DownloadActionHookTest', '*OfficialLivePhotoDownloadTest', '*ConfigTransferTest',
        '*PreviewSettingsIsolationTest', '*LocalMediaStorageTest', '*OfficialDownloadDialogTest',
        '*NativeMessagingHooksTest', '*NativeMessagingPolicyTest', '*NativeCommentReply*Test',
        '*CommentReplyHookTest', '*CommentReplyLedgerTest', '*AutoCommentHookTest', '*AutoCommentQueueTest',
        '*CommentVoiceHookTest', '*NativeCommentSpeechHookTest', '*DirectMessageSpeechHookTest',
        '*DirectMessageSpeechFilesTest', '*AutomationRateLimiterTest', '*SubmissionLedgerTest',
        '*ConfigurationCatalogTest', '*ConfigBackupTest', '*CommentMediaActionsIntegrationTest',
        '*SpeechCacheDirectoryTest', '*DesktopSettingsScreenTest', '*ModuleReceiptStatusTest',
        '*MainActivityDiagnosticsTest', '*ModuleStatusScreenTest', '*HostStartup*Test',
        '*HostSettingsEntryTest', '*HostUiControllerTest', '*ModuleSettings*Test',
        '*ModuleBridgeProviderTest', '*HostConfigBridgeTest', '*FrameworkConfigSyncTest',
        '*HostSettingsStoreTest', '*HostSettingsMigrationTest', '*HostApkProfileTest',
        '*ScanDiagnosticsChannelTest', '*Legacy*Test', '*SpeechSettingsDialogTest',
        '*AudioPathImportTest', '*Host*PickerTest', '*Download412*Test', '*MediaModel412SourceTest',
        '*MediaModelHookTest', '*ConfigurationDownloadPublicationTest'
    )
    $gradleArguments = @(':app:testDebugUnitTest')
    foreach ($pattern in $testPatterns) { $gradleArguments += @('--tests', $pattern) }
    $gradleArguments += @('--max-workers=2', '--console=plain')
    & '.\gradlew.bat' @gradleArguments
    if ($LASTEXITCODE -ne 0) { throw 'Unified regression tests failed' }
    & '.\gradlew.bat' ':app:assembleDebug' ':app:assembleLegacy' '--max-workers=2' '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw 'Unified APK build failed' }
    if ($RunLint) {
        Write-Host 'Lint: excludes only four PrivateApi detector IDs because Java PSI cannot parse legal obfuscated DEX names. See docs.'
        & '.\gradlew.bat' ':app:lintDebug' ':app:lintLegacy' '-PhostDexLintWorkaround=true' '--max-workers=2' '--console=plain'
        if ($LASTEXITCODE -ne 0) { throw 'Lint did not complete successfully; inspect the separate lint report' }
    }
} finally { Pop-Location }
