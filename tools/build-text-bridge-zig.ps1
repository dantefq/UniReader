param([Parameter(Mandatory=$true)][string]$Zig)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$source=Join-Path $projectRoot 'app/src/main/cpp'
$temporary=Join-Path $projectRoot '.native-build'
New-Item -ItemType Directory -Force $temporary | Out-Null
$env:ZIG_GLOBAL_CACHE_DIR=Join-Path $temporary 'cache'
# This link-time stub supplies only a libc.so import. It is NEVER packaged.
# Android resolves free() from its real system libc.so at runtime.
$stubSource=Join-Path $temporary 'libc-stub.c'
[IO.File]::WriteAllText($stubSource,'void free(void* pointer) {(void)pointer;}')
foreach($pair in @(@('aarch64-linux-android','arm64','arm64-v8a'),@('x86_64-linux-android','emulator','x86_64'))){
    $target=$pair[0];$flavor=$pair[1];$abi=$pair[2]
    $stub=Join-Path $temporary ($flavor+'-libc.so')
    & $Zig cc -target $target -ffreestanding -fno-stack-protector -fno-builtin -fPIC -shared -nostdlib '-Wl,-soname,libc.so' $stubSource -o $stub
    if($LASTEXITCODE -ne 0){throw 'Import stub build failed'}
    $folder=Join-Path $projectRoot ('app/src/'+$flavor+'/bridgeLibs/'+$abi)
    New-Item -ItemType Directory -Force $folder | Out-Null
    & $Zig cc -target $target -std=c11 -O2 -ffreestanding -fno-stack-protector -fno-builtin -fPIC -shared -nostdlib -I (Join-Path $source 'include') '-Wl,-z,max-page-size=16384' '-Wl,-z,defs' '-Wl,-soname,libunireader-text.so' (Join-Path $source 'textbridge.c') $stub -o (Join-Path $folder 'libunireader-text.so')
    if($LASTEXITCODE -ne 0){throw 'JNI bridge build failed'}
}
