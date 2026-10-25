$projectRoot=Split-Path -Parent $PSScriptRoot
$settingsPath=Join-Path $projectRoot '.tools/stage-b-db.json'
if(-not(Test-Path $settingsPath)){throw '请先运行 scripts/bootstrap-stage-b-db.ps1，或自行显式提供数据库环境变量'}
$db=Get-Content $settingsPath -Raw | ConvertFrom-Json
$parameters='?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&useSSL=false'
$env:DB_URL='jdbc:mysql://127.0.0.1:13306/yueji_mall_dev'+$parameters
$env:DB_USERNAME='yueji_dev'
$env:DB_PASSWORD=$db.devPassword
$env:FILE_STORAGE_ROOT=Join-Path $projectRoot '.artifacts/storage/dev'
$env:YUEJI_TEST_DB_URL='jdbc:mysql://127.0.0.1:13306/yueji_mall_test'+$parameters
$env:YUEJI_TEST_DB_USERNAME='yueji_test'
$env:YUEJI_TEST_DB_PASSWORD=$db.testPassword
