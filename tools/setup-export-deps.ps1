param([switch]$Offline)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$root=Split-Path $PSScriptRoot -Parent
$cache=Join-Path $root '.tools/export-deps'
New-Item -ItemType Directory -Force -Path $cache | Out-Null
$hashes=@{
 'jump3r-1.0.5.jar'='89A5894EEE514F9E86A61F01D59B2665AF6EDDCC25B01ACCC86514BAE16F0054'
 'jump3r-1.0.5-sources.jar'='BBD353FCB01D6192B7FAF00939CBB4F65E31C6911DE4972007AF0DD9775614E9'
}
foreach($name in $hashes.Keys) {
 $path=Join-Path $cache $name
 if(-not (Test-Path -LiteralPath $path)) {
  if($Offline) { throw "Missing cached $name; run tools/setup-export-deps.ps1 online first" }
  Invoke-WebRequest -UseBasicParsing -Uri ('https://repo.maven.apache.org/maven2/de/sciss/jump3r/1.0.5/'+$name) -OutFile $path
 }
 if((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $hashes[$name]) { throw "Checksum mismatch: $name" }
}
# Only portable encoder/decoder packages; no desktop frontends. Library classes unchanged.
$target=Join-Path $cache 'jump3r-android.jar'
if(Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target }
$source=[IO.Compression.ZipFile]::OpenRead((Join-Path $cache 'jump3r-1.0.5.jar'))
$dest=[IO.Compression.ZipFile]::Open($target,[IO.Compression.ZipArchiveMode]::Create)
try {
 foreach($entry in $source.Entries) {
  if($entry.FullName -notmatch '^de/sciss/jump3r/(mp3|mpg)/.*\.class$') { continue }
  $copy=$dest.CreateEntry($entry.FullName); $input=$entry.Open(); $output=$copy.Open()
  try { $input.CopyTo($output) } finally { $input.Dispose(); $output.Dispose() }
 }
} finally { $source.Dispose(); $dest.Dispose() }
Write-Output 'MP3 encoder verified and prepared'