param([ValidateSet('arm64','emulator')][string[]]$Architectures=@('arm64','emulator'))
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$engineCache=Join-Path $projectRoot '.engine'
[IO.Directory]::CreateDirectory($engineCache) | Out-Null
$specs=@{
    arm64=@{Code=131;Sha256='751ac3836890b79a56801bf6d9a2788cda3761ab5131a4f0b6abfbad822bb475'}
    emulator=@{Code=129;Sha256='cfaf9eea5fd695c449e92eb10cc0b1617ec51d068345068cea9b7e1c67968df5'}
}
foreach($architecture in $Architectures){
    $spec=$specs[$architecture]
    $name='org.documentfoundation.libreoffice_'+$spec.Code+'.apk'
    $apk=Join-Path $engineCache $name
    if(!(Test-Path -LiteralPath $apk)){
        Write-Host "Downloading LibreOffice 26.2.6.3 for $architecture"
        Invoke-WebRequest -Uri ('https://f-droid.org/repo/'+$name) -UserAgent 'F-Droid/1.22.0' -OutFile $apk
    }
    $hash=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    if($hash -ne $spec.Sha256){throw "SHA-256 mismatch: $apk"}
    $archive=[IO.Compression.ZipFile]::OpenRead($apk)
    try{
        foreach($entry in $archive.Entries){
            $relative=$null
            if($entry.FullName.StartsWith('lib/')){$relative='app/src/'+$architecture+'/jniLibs/'+$entry.FullName.Substring(4)}
            if($entry.FullName.StartsWith('assets/') -and !$entry.FullName.StartsWith('assets/dexopt/')){$relative='app/src/main/'+$entry.FullName}
            if($relative -and !$entry.FullName.EndsWith('/')){
                $target=[IO.Path]::GetFullPath((Join-Path $projectRoot $relative))
                if(!$target.StartsWith($projectRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe archive entry'}
                [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target)) | Out-Null
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$target,$true)
            }
        }
    }finally{$archive.Dispose()}
}
& (Join-Path $PSScriptRoot 'configure-engine.ps1')
Write-Host 'Engine files verified and prepared. Build with gradlew.bat :app:assembleArm64Debug'
