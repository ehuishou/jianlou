$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
& (Join-Path $PSScriptRoot 'setup-export-deps.ps1') -Offline
$out=Join-Path $root ('build/export-tests/'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $out | Out-Null
$jar=Join-Path $root '.tools/export-deps/jump3r-android.jar'
$encoder=Join-Path $root 'app/src/main/java/com/example/offlineeditor/export/Mp3Encoder.java'
$test=Join-Path $root 'app/src/test/java/com/example/offlineeditor/export/Mp3EncoderTest.java'
& javac --release 8 -encoding UTF-8 -cp $jar -d $out $encoder $test
if($LASTEXITCODE -ne 0) { throw 'MP3 test compilation failed' }
& java -cp "$out;$jar" com.example.offlineeditor.export.Mp3EncoderTest (Join-Path $out 'fixtures')
if($LASTEXITCODE -ne 0) { throw 'MP3 encoding tests failed' }
Write-Output ('Fixtures: '+(Join-Path $out 'fixtures'))
