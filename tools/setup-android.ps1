param([switch]$IncludeAdb)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$toolRoot = Join-Path $root '.tools'
$sdk = Join-Path $toolRoot 'android-sdk'
$downloads = Join-Path $toolRoot 'downloads'
New-Item -ItemType Directory -Force -Path $downloads,$sdk | Out-Null
$repoPath = Join-Path $downloads 'repository2-1.xml'
& curl.exe -fL --retry 2 --connect-timeout 20 --max-time 120 'https://dl.google.com/android/repository/repository2-1.xml' -o $repoPath
if ($LASTEXITCODE -ne 0) { throw 'Android metadata download failed.' }
[xml]$repo = Get-Content -LiteralPath $repoPath -Raw
$packages = @(
 @{ Id='platforms;android-35'; Archive='platform-35_r02.zip'; Destination='platforms/android-35'; Marker='android.jar' },
 @{ Id='build-tools;35.0.0'; Archive='build-tools_r35_windows.zip'; Destination='build-tools/35.0.0'; Marker='aapt2.exe' }
)
if ($IncludeAdb) { $packages += @{ Id='platform-tools'; Archive=$null; Destination='platform-tools'; Marker='adb.exe' } }
foreach ($spec in $packages) {
 $destination = Join-Path $sdk $spec.Destination
 if (Test-Path -LiteralPath (Join-Path $destination $spec.Marker)) { Write-Output ('Already installed: '+$spec.Id); continue }
 $archive = $null
 foreach ($package in @($repo.SelectNodes('//*[local-name()="remotePackage"]') | Where-Object { $_.path -eq $spec.Id })) {
  foreach ($a in $package.archives.archive) {
   if (((-not $a.'host-os') -or $a.'host-os' -eq 'windows') -and ((-not $spec.Archive) -or $a.complete.url -eq $spec.Archive)) { $archive = $a }
  }
 }
 if (-not $archive) { throw ('Pinned archive not found: '+$spec.Id) }
 $zip = Join-Path $downloads ([string]$archive.complete.url)
 if (-not (Test-Path -LiteralPath $zip)) {
  & curl.exe -fL --retry 2 --connect-timeout 20 --max-time 600 ('https://dl.google.com/android/repository/'+$archive.complete.url) -o $zip
  if ($LASTEXITCODE -ne 0) { throw ('Download failed: '+$spec.Id) }
 }
 if ((Get-FileHash -LiteralPath $zip -Algorithm SHA1).Hash -ne [string]$archive.complete.checksum) { throw ('SHA1 mismatch: '+$zip) }
 $stage = Join-Path $downloads ([Guid]::NewGuid().ToString('N'))
 Expand-Archive -LiteralPath $zip -DestinationPath $stage
 $entries = @(Get-ChildItem -LiteralPath $stage -Directory)
 if ($entries.Count -ne 1) { throw 'Unexpected SDK archive layout.' }
 New-Item -ItemType Directory -Path (Split-Path $destination -Parent) -Force | Out-Null
 $sourcePath = [IO.Path]::GetFullPath($entries[0].FullName)
 $targetPath = [IO.Path]::GetFullPath($destination)
 $allowed = [IO.Path]::GetFullPath($toolRoot) + [IO.Path]::DirectorySeparatorChar
 if (-not $sourcePath.StartsWith($allowed) -or -not $targetPath.StartsWith($allowed)) { throw 'SDK move escaped workspace.' }
 Move-Item -LiteralPath $sourcePath -Destination $targetPath
 Write-Output ('Installed: '+$spec.Id)
}
Write-Output ('SDK: '+$sdk)
