$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$root=Split-Path $PSScriptRoot -Parent
$javaBin=(Get-ChildItem -LiteralPath 'C:\Program Files\Java' -Recurse -Filter 'jar.exe' -ErrorAction SilentlyContinue | Select-Object -First 1 | ForEach-Object { $_.Directory.FullName })
if(-not $javaBin) { throw 'JDK jar.exe not found' }
$sdk=Join-Path $root '.tools/android-sdk'
$bt=Join-Path $sdk 'build-tools/35.0.0'
$android=Join-Path $sdk 'platforms/android-35/android.jar'
$build=Join-Path $root ('build/direct/'+[Guid]::NewGuid().ToString('N'))
$classes=Join-Path $build 'classes'
$dex=Join-Path $build 'dex'
$generated=Join-Path $build 'generated'
New-Item -ItemType Directory -Force -Path $classes,$dex,$generated | Out-Null
function Check($step) { if($LASTEXITCODE -ne 0) { throw ($step+' failed: '+$LASTEXITCODE) } }
& (Join-Path $bt 'aapt2.exe') compile --dir (Join-Path $root 'app/src/main/res') -o (Join-Path $build 'resources.zip')
Check 'Resource compilation'
& (Join-Path $bt 'aapt2.exe') link -o (Join-Path $build 'unsigned.apk') --manifest (Join-Path $root 'app/src/main/AndroidManifest.xml') -I $android --java $generated (Join-Path $build 'resources.zip')
Check 'Resource linking'
$sources=@(Get-ChildItem -LiteralPath (Join-Path $root 'core/src/main/java'),(Join-Path $root 'app/src/main/java'),$generated -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
& javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -cp $android -d $classes $sources
Check 'Java compilation'
$jar=Join-Path $build 'classes.jar'
& (Join-Path $javaBin 'jar.exe') cf $jar -C $classes .
Check 'Class archive'
& (Join-Path $bt 'd8.bat') --lib $android --min-api 26 --output $dex $jar
Check 'DEX compilation'
$unsigned=Join-Path $build 'unsigned.apk'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[IO.Compression.ZipFile]::Open($unsigned,[IO.Compression.ZipArchiveMode]::Update)
try { foreach($f in Get-ChildItem -LiteralPath $dex -Filter '*.dex') { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$f.FullName,$f.Name) | Out-Null } } finally { $archive.Dispose() }
$aligned=Join-Path $build 'aligned.apk'
& (Join-Path $bt 'zipalign.exe') -f -p 4 $unsigned $aligned
Check 'APK alignment'
$key=Join-Path $root '.tools/debug.keystore'
if(-not (Test-Path -LiteralPath $key)) {
 & (Join-Path $javaBin 'keytool.exe') -genkeypair -keystore $key -storepass android -keypass android -alias androiddebugkey -dname 'CN=Android Debug,O=Offline Editor,C=US' -keyalg RSA -keysize 2048 -validity 10000
 Check 'Debug key generation'
}
$output=Join-Path $root 'build/jianlou-dev.apk'
& (Join-Path $bt 'apksigner.bat') sign --ks $key --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $output $aligned
Check 'APK signing'
& (Join-Path $bt 'apksigner.bat') verify --verbose $output
Check 'Signature verification'
& (Join-Path $bt 'aapt.exe') dump badging $output
Check 'APK metadata'
Get-FileHash -LiteralPath $output -Algorithm SHA256
Write-Output ('APK: '+$output)





