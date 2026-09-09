$ErrorActionPreference = 'Stop'
$asterRoot = Split-Path $PSScriptRoot -Parent
$asterBuild = Join-Path $asterRoot 'build'
$asterCompilerDir = Join-Path $asterBuild 'inno'
$asterCompiler = Join-Path $asterCompilerDir 'ISCC.exe'
if (-not (Test-Path -LiteralPath $asterCompiler)) {
    $asterDownload = Join-Path $asterBuild 'inno-setup.exe'
    Invoke-WebRequest -Uri 'https://github.com/jrsoftware/issrc/releases/download/is-7_1_0/innosetup-7.1.0-x64.exe' -OutFile $asterDownload
    $asterHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $asterDownload).Hash.ToLowerInvariant()
    if ($asterHash -ne '0362a383ed217d4c4239b5933866dd96d3eb2102737da92f80f6057a4b40df2f') {
        throw 'Inno Setup download failed its pinned SHA-256 check'
    }
    $asterInstall = Start-Process -FilePath $asterDownload -ArgumentList @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/SP-', "/DIR=`"$asterCompilerDir`"") -Wait -PassThru
    if ($asterInstall.ExitCode -ne 0) { throw 'Could not install the packaging compiler' }
}
$asterMetadata = Get-Content -Raw -LiteralPath (Join-Path $asterBuild 'VERSION.json') | ConvertFrom-Json
& $asterCompiler "/DAsterVersion=$($asterMetadata.version)" "/DAsterNativeVersion=$($asterMetadata.native_version)" (Join-Path $PSScriptRoot 'windows.iss')
if ($LASTEXITCODE -ne 0) { throw 'Windows installer compilation failed' }
