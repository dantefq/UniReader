$ErrorActionPreference='Stop'
$registry=Join-Path $PSScriptRoot '../app/src/main/assets/share/registry/main.xcd'
$content=[IO.File]::ReadAllText($registry)
foreach($property in @('DisableMacrosExecution','DisableActiveContent','DisableOLEAutomation')){
    $pattern='(<prop ns0:name="'+$property+'"[^>]*>.*?<value>)(false|true)(</value>)'
    $matches=[regex]::Matches($content,$pattern,[Text.RegularExpressions.RegexOptions]::Singleline)
    if($matches.Count -ne 1){throw "Cannot locate LibreOffice security setting: $property"}
    $replacement=if($property -eq 'DisableActiveContent'){'${1}false${3}'}else{'${1}true${3}'}
    $content=[regex]::Replace($content,$pattern,$replacement,[Text.RegularExpressions.RegexOptions]::Singleline)
}
[IO.File]::WriteAllText($registry,$content,[Text.UTF8Encoding]::new($false))
$content=[IO.File]::ReadAllText($registry)
$csvPattern='(<prop ns0:name="WarnActiveSheet"[^>]*>.*?<value>)(false|true)(</value>)'
if([regex]::Matches($content,$csvPattern,[Text.RegularExpressions.RegexOptions]::Singleline).Count -ne 1){throw 'Cannot locate Calc WarnActiveSheet'}
$content=[regex]::Replace($content,$csvPattern,'${1}false${3}',[Text.RegularExpressions.RegexOptions]::Singleline)
[IO.File]::WriteAllText($registry,$content,[Text.UTF8Encoding]::new($false))
