<# Compatibility guard retained at the previous download location.
   Standalone Aster has no Windows build yet. No other browser is installed. #>
[CmdletBinding()]
param([switch]$Check)

Write-Output 'Standalone Aster for Windows: no installable build has been published yet.'
Write-Output 'This file does not install Firefox, Chrome, Chromium, or the retired companion.'
Write-Output 'Platform status: https://github.com/xatusbetazx17/aster-browser/blob/codex/aster-webkit-desktop/docs/setup/windows.md'
if ($Check) { exit 0 }
exit 2
