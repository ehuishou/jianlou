$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$out=Join-Path $root ('build/core-tests/'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $out -Force | Out-Null
$sources=@(Get-ChildItem -LiteralPath (Join-Path $root 'core/src') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
& javac --release 8 -encoding UTF-8 -d $out $sources
if($LASTEXITCODE -ne 0) { throw 'Core compilation failed' }
& java -cp $out com.example.offlineeditor.core.CoreTest (Join-Path $out 'fixtures')
if($LASTEXITCODE -ne 0) { throw 'Core tests failed' }
