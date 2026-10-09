# Copies the Windows packages of the last Gradle packaging run into dist-artifacts/, named for the release:
# PSD2Live-<version><suffix>.exe and PSD2Live-<version>-windows-x86_64-portable<suffix>.zip; an arm64 build is
# PSD2Live-<version>-arm64<suffix>.exe and PSD2Live-<version>-windows-arm64-portable<suffix>.zip.
param(
	[Parameter(Mandatory = $true)][string]$Version,
	[string]$Suffix = "",
	[ValidateSet("x86_64", "arm64")][string]$Arch = "x86_64"
)
$ErrorActionPreference = "Stop"
$out = "dist-artifacts"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$binaries = "build/compose/binaries/main"

$app = Join-Path $binaries "app/PSD2Live"
if (-not (Test-Path (Join-Path $app "PSD2Live.exe"))) { throw "No app image at $app" }
# The app image of an ffmpeg build carries ffmpeg; until 3.1.2 a stale image made the -ffmpeg packages the plain ones.
$ffmpeg = Test-Path (Join-Path $app "app/resources/ffmpeg/ffmpeg.exe")
if ($Suffix -like "*ffmpeg*" -and -not $ffmpeg) { throw "The app image at $app has no app/resources/ffmpeg/ffmpeg.exe" }
if ($Suffix -notlike "*ffmpeg*" -and $ffmpeg) { throw "The app image at $app carries ffmpeg but -Suffix is '$Suffix'" }
$portable = Join-Path (Resolve-Path $out) "PSD2Live-$Version-windows-$Arch-portable$Suffix.zip"
if (Test-Path $portable) { Remove-Item $portable -Force }
Compress-Archive -Path (Join-Path $app "*") -DestinationPath $portable -Force

# packageExe names the arm64 installer PSD2Live-<version>-arm64.exe (packaging/windows/psd2live.iss).
$installerName = if ($Arch -eq "arm64") { "PSD2Live-$Version-arm64" } else { "PSD2Live-$Version" }
foreach ($type in "exe") {
	$installer = Join-Path $binaries "$type/$installerName.$type"
	if (-not (Test-Path $installer)) {
		Get-ChildItem $binaries -Recurse -File -ErrorAction SilentlyContinue | Select-Object -First 50 FullName
		throw "No $type installer at $installer (does packageVersion in build.gradle.kts match $Version?)"
	}
	$length = (Get-Item $installer).Length
	if ($length -lt 5MB) { throw "$installer is only $length bytes" }
	Copy-Item $installer (Join-Path $out "$installerName$Suffix.$type")
}
Get-ChildItem $out | Format-Table Name, Length
