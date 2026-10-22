# Repository Regression Tests

## Supported Platform

- Minimum Android version: Android 13, API 33.
- Compile and target SDK: 36.
- Real-device validation: Samsung SM-G9810 running Android 13 / API 33.
- Local repositories use app-private storage, without shared-storage or media permissions.

## Run On A Connected Device

Use a JDK and Android SDK configured for this project. On macOS, Android Studio's
bundled JDK can be selected when the system Java installation is unavailable:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
adb devices -l
export ANDROID_SERIAL="<connected-device-serial>"
./gradlew :app:testDebugUnitTest --rerun-tasks
./gradlew :app:connectedDebugAndroidTest
```

Unlock the device before starting and keep it awake during instrumentation.
Restore any temporary stay-awake setting after debugging. Do not clear app data
or grant broad storage permissions to make the tests pass.

Reports:

- JVM: `app/build/reports/tests/testDebugUnitTest/index.html`
- Device: `app/build/reports/androidTests/connected/debug/index.html`
- Machine-readable device results: `app/build/outputs/androidTest-results/connected/`

## Coverage

| Boundary | Tests | Behaviors |
| --- | --- | --- |
| Repositories UI | `LocalRepositoriesUiTest` | Creation and automatic detail navigation, separate display name, invalid/existing/retained names, import confirmation and cancellation, existing Git configuration preservation, duplicate imports, scoped manual paths, persistent failure, database reopen and retry, separate removal/deletion confirmations, remote Clone validation. |
| Large-directory UI | `LocalRepositoriesUiTest` | Virtualized picker with 250 other directories, import and status display for 1,500 files, and responsive tab/navigation interactions. |
| Repository lifecycle | `RepositoryLifecycleTest` | Real Room/JGit/filesystem creation and import, concurrent duplicate rejection, session/message preservation, actual database close/reopen recovery, cancellation and shutdown, failed initialization cleanup, permission errors, path ownership and symbolic-link safety, stale deletion confirmation and idempotence, offline Clone lifecycle. |
| Persistence | `TiDaoTest`, `TiDatabaseMigrationTest` | Duplicate paths fail rather than replace existing conversations, nullable remote metadata, persisted states, migrations preserving repository/session/message data and foreign-key cascades. |
| Git | `GitServiceTest` | Initialization, exact-root identification, nonempty directory handling, file-based Clone with original content/configuration, status, diff, commits, branches and remote configuration. |
| Agent integration | `OfflineAgentRegressionTest` | Real Room, streamed model parsing, file tools and Git commit behind approval, plus repository-free chat. Responses come from a loopback HTTP server. |
| Platform | `AndroidPlatformTest` | The installed APK has minSdk 33 and requests no external-storage or media permissions. |
| Existing app | `TiAppNavigationTest`, `EdgeToEdgeTest`, `SessionModelSwitchTest`, JVM Agent tests | Root tabs, actual Add repository actions, system/IME insets, session model selection and existing agent behavior. |

## Isolation And External Services

The new repository UI/lifecycle tests own uniquely named temporary directories and
disk databases. Reopening a container also reopens the database, rather than only
recreating an Activity. Cleanup closes owned jobs and removes only these fixtures.

The offline Agent tests own an in-memory database, temporary repositories and a
loopback model server. File-based Clone tests use a local origin. These tests do
not read configured provider credentials, contact a real provider, or push to a
remote repository.

Existing navigation/inset tests launch `MainActivity`; their temporary session and
provider records are uniquely identified and cleaned up. The user's database is
not cleared.

`RealLlmAgentE2ETest` contains two explicitly opt-in external-provider tests. They
remain skipped in the default regression run. Passing the offline suite does not
claim that a live provider was exercised.

Final real-device evidence and acceptance status are recorded in GitHub issue #6.
