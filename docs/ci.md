# Continuous integration

The `CI` GitHub Actions workflow runs on pull requests and pushes to `main`.

| Check | Hosted runner | Coverage |
| --- | --- | --- |
| Android build and JVM tests | Ubuntu 24.04 | Gradle `:app:assembleDebug` and `:app:testDebugUnitTest`, plus patch whitespace check |
| Windows native and receiver checks | Windows Server 2022 | CMake x64 Debug build, CTest, .NET 10 receiver build, receiver wire self-test, and Media Foundation decoder create/reset smoke test |

The Windows job builds the native decoder before the .NET receiver so the existing project target copies the DLL into its output directory. The workflow uses only GitHub-hosted runners and public dependencies. It requires no device or repository secret.

These checks are automated build, parser, native-unit, and decoder-initialization evidence. They do not establish physical S21 Ultra capture, desktop preview, or virtual-camera consumer behavior; record those results in the milestone validation documents.

The WinUI project from #22 is being developed separately. Once its project layout and hosted-runner build prerequisites are known, add its build to the Windows job without repeating the native build.
