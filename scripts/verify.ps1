param([switch]$Native, [ValidateSet(17,21,25)][int]$TestJavaVersion=25)
$ErrorActionPreference='Stop'
$coreRoot=Split-Path -Parent $PSScriptRoot
$gradleArgs=@('check',"-PspmTestJavaVersion=$TestJavaVersion",'--console=plain')
if($Native){
    if($env:RUSTFLAGS -match 'target-cpu[= ]native'){throw 'A portable native candidate cannot use target-cpu=native'}
    $rustRoot=Join-Path $coreRoot 'native-rust'
    $cargoPrefix=@()
    $gnuTools='C:\msys64\ucrt64\bin'
    $toolchains=(& rustup toolchain list) -join "`n"
    if($IsWindows -and $toolchains -match '1\.98\.1-x86_64-pc-windows-gnu' -and (Test-Path -LiteralPath (Join-Path $gnuTools 'gcc.exe')) -and -not (Get-Command link.exe -ErrorAction SilentlyContinue)){
        $cargoPrefix=@('+1.98.1-x86_64-pc-windows-gnu')
        $env:PATH=$gnuTools+';'+$env:PATH
    }
    $manifest=Join-Path $rustRoot 'Cargo.toml'
    & cargo @cargoPrefix fmt --manifest-path $manifest --all --check
    if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
    & cargo @cargoPrefix clippy --manifest-path $manifest --workspace --all-targets --locked -- -D warnings
    if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
    & cargo @cargoPrefix test --manifest-path $manifest --workspace --locked
    if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
    & cargo @cargoPrefix build --manifest-path $manifest --release --locked
    if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
    $metadata=(& cargo @cargoPrefix metadata --manifest-path $manifest --no-deps --format-version 1 | ConvertFrom-Json)
    $libraryName=if($IsWindows){'spm_core_native.dll'}elseif($IsMacOS){'libspm_core_native.dylib'}else{'libspm_core_native.so'}
    $library=Join-Path (Join-Path $metadata.target_directory 'release') $libraryName
    if(-not (Test-Path -LiteralPath $library)){throw "Native output missing: $library"}
    $gradleArgs+="-PspmNativeLibrary=$library"
}
# Gradle uses project-dir explicitly, so the caller's working directory is irrelevant.
$wrapper=if($IsWindows){Join-Path $coreRoot 'gradlew.bat'}else{Join-Path $coreRoot 'gradlew'}
& $wrapper --project-dir $coreRoot @gradleArgs
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
# Isolation and artifact contents are part of verification, rather than an optional manual step.
# Python >=3.11 is required by the evidence tools (stdlib tomllib).
$python=Get-Command python -ErrorAction Stop
& $python.Source -m unittest discover -s (Join-Path $coreRoot 'scripts') -p 'test_*.py'
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
& $python.Source (Join-Path $coreRoot 'scripts/audit_core.py') --root $coreRoot --require-jars --output (Join-Path $coreRoot 'build/reports/core-audit.json')
exit $LASTEXITCODE
