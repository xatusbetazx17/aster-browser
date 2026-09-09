<# Compatibility guard retained at the previous download location.
   Full-featured Windows Aster is unfinished; the engine preview has a separate package. #>
[CmdletBinding()]
param([switch]$Check)

Write-Output 'The full-featured Windows Aster browser is still unfinished.'
Write-Output 'A basic original-engine EXE preview is available separately; this script does not install it.'
Write-Output 'Preview instructions: https://github.com/xatusbetazx17/aster-browser/blob/codex/aster-webkit-desktop/experiments/aster-engine/README.md'
Write-Output 'Platform status: https://github.com/xatusbetazx17/aster-browser/blob/codex/aster-webkit-desktop/docs/setup/windows.md'
if ($Check) { exit 0 }
exit 2
