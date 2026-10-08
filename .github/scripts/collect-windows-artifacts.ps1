# Copies the Windows packages of the last Gradle packaging run into dist-artifacts/, named for the release:
# PSD2Live-<version><suffix>.exe / .msi and PSD2Live-<version>-windows-x86_64-portable<suffix>.zip.
param(
	[Parameter(Mandatory = $true)][string]$Version,
	[string]$Suffix = ""
)
$ErrorActionPreference = "Stop"
$out = "dist-artifacts"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$binaries = "build/compose/binaries/main"

$app = Join-Path $binaries "app/PSD2Live"
if (-not (Test-Path (Join-Path $app "PSD2Live.exe"))) { throw "No app image at $app" }
$portable = Join-Path (Resolve-Path $out) "PSD2Live-$Version-windows-x86_64-portable$Suffix.zip"
if (Test-Path $portable) { Remove-Item $portable -Force }
Compress-Archive -Path (Join-Path $app "*") -DestinationPath $portable -Force

foreach ($type in "exe", "msi") {
	$installer = Join-Path $binaries "$type/PSD2Live-$Version.$type"
	if (-not (Test-Path $installer)) {
		Get-ChildItem $binaries -Recurse -File -ErrorAction SilentlyContinue | Select-Object -First 50 FullName
		throw "No $type installer at $installer (does packageVersion in build.gradle.kts match $Version?)"
	}
	$length = (Get-Item $installer).Length
	if ($length -lt 5MB) { throw "$installer is only $length bytes" }
	Copy-Item $installer (Join-Path $out "PSD2Live-$Version$Suffix.$type")
}
Get-ChildItem $out | Format-Table Name, Length
