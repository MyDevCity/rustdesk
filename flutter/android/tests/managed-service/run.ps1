param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$GradleUserHome = (Join-Path $env:USERPROFILE '.gradle')
)
$ErrorActionPreference = 'Stop'
if (!$JavaHome) { throw 'Set JAVA_HOME to a JDK 17 installation.' }
$taskJava = Join-Path $JavaHome 'bin/java.exe'
$taskCache = Join-Path $GradleUserHome 'caches/modules-2/files-2.1'
$taskDependencies = @(
    'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.20',
    'org.jetbrains.kotlin/kotlin-stdlib/2.1.20',
    'org.jetbrains.kotlin/kotlin-script-runtime/2.1.20',
    'org.jetbrains.kotlin/kotlin-reflect/1.6.10',
    'org.jetbrains.kotlin/kotlin-daemon-embeddable/2.1.20',
    'org.jetbrains.intellij.deps/trove4j/1.0.20200330',
    'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0',
    'org.jetbrains/annotations'
)
$taskJars = @($taskDependencies | ForEach-Object {
    Get-ChildItem (Join-Path $taskCache $_) -Filter '*.jar' -Recurse | Select-Object -ExpandProperty FullName
})
$taskClasspath = $taskJars -join ';'
$taskOutput = Join-Path $env:TEMP 'nks-managed-service-test'
$taskSources = @(Get-ChildItem $PSScriptRoot -Filter '*.kt' | Select-Object -ExpandProperty FullName)
$taskService = Join-Path $PSScriptRoot '../../app/src/main/kotlin/com/carriez/flutter_hbb/NksManagedService.kt'
& $taskJava -cp $taskClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath $taskClasspath -d $taskOutput @taskSources $taskService
if ($LASTEXITCODE -ne 0) { throw 'Managed service test compilation failed.' }
& $taskJava -cp "$taskOutput;$taskClasspath" com.carriez.flutter_hbb.ReproKt
if ($LASTEXITCODE -ne 0) { throw 'Managed service regression failed.' }
