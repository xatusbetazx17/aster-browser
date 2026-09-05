<# Run again to install or update Aster Companion source for official Firefox.
   No browser signature, execution-policy, TLS or sandbox settings are changed. #>
[CmdletBinding()]
param(
    [string]$InstallDir,
    [switch]$SkipDependencies,
    [switch]$Check,
    [switch]$Rollback
)
$ErrorActionPreference = 'Stop'

function Find-AsterPython {
    $candidates = @()
    $pyCommand = Get-Command py -ErrorAction SilentlyContinue
    if ($pyCommand) { $candidates += @{ Exe = $pyCommand.Source; Prefix = @('-3') } }
    $pythonCommand = Get-Command python -ErrorAction SilentlyContinue
    if ($pythonCommand -and $pythonCommand.Source -notlike '*\WindowsApps\*') {
        $candidates += @{ Exe = $pythonCommand.Source; Prefix = @() }
    }
    foreach ($candidatePath in @(
        (Join-Path $env:LOCALAPPDATA 'Programs\Python\Python313\python.exe'),
        (Join-Path $env:ProgramFiles 'Python313\python.exe')
    )) {
        if (Test-Path -LiteralPath $candidatePath) { $candidates += @{ Exe = $candidatePath; Prefix = @() } }
    }
    foreach ($candidate in $candidates) {
        $exe = $candidate.Exe
        $prefix = $candidate.Prefix
        & $exe @prefix -c 'import sys; sys.exit(0 if sys.version_info >= (3,10) else 1)' 2>$null
        if ($LASTEXITCODE -eq 0) { return $candidate }
    }
    return $null
}

$taskPython = Find-AsterPython
if (-not $taskPython) {
    if ($Check -or $SkipDependencies -or $Rollback) { throw 'Python 3.10+ is required. Install Python and run setup again.' }
    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
        throw 'Install Python from python.org or Microsoft Store, then run this file again. WinGet is unavailable.'
    }
    & winget install --id Python.Python.3.13 --exact --source winget --scope user
    if ($LASTEXITCODE -ne 0) { throw 'Python installation failed or was canceled.' }
    $taskPython = Find-AsterPython
    if (-not $taskPython) { throw 'Python was not found. Reopen PowerShell and run this file again.' }
}

$taskArguments = @('--edition', 'firefox')
if ($InstallDir) { $taskArguments += @('--install-dir', $InstallDir) }
if ($SkipDependencies) { $taskArguments += '--skip-dependencies' }
if ($Check) { $taskArguments += '--check' }
if ($Rollback) { $taskArguments += '--rollback' }
$taskSetup = Join-Path $PSScriptRoot 'setup.py'
$taskTemporary = $null
try {
    if (-not (Test-Path -LiteralPath $taskSetup)) {
        $taskTemporary = Join-Path ([IO.Path]::GetTempPath()) ('aster-setup-' + [Guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $taskTemporary | Out-Null
        $taskSetup = Join-Path $taskTemporary 'setup.py'
        Invoke-WebRequest -UseBasicParsing -Uri 'https://raw.githubusercontent.com/xatusbetazx17/aster-browser/codex/aster-webkit-desktop/installers/setup.py' -OutFile $taskSetup
    }
    $taskExe = $taskPython.Exe
    $taskPrefix = $taskPython.Prefix
    & $taskExe @taskPrefix $taskSetup @taskArguments
    $taskExitCode = $LASTEXITCODE
} finally {
    if ($taskTemporary) { Remove-Item -LiteralPath $taskTemporary -Recurse -Force }
}
exit $taskExitCode
