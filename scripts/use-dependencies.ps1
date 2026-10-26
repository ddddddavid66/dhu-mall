$ErrorActionPreference = 'Stop'
$taskProjectRoot = Split-Path -Parent $PSScriptRoot
$taskCredentials = Join-Path $taskProjectRoot '.artifacts/R-dependencies.json'
if (-not (Test-Path -LiteralPath $taskCredentials)) { throw '先执行 bootstrap-dependencies.ps1；不会生成生产凭据' }
$taskConfig = Get-Content -LiteralPath $taskCredentials -Raw | ConvertFrom-Json
$env:REDIS_HOST='127.0.0.1'
$env:REDIS_PORT='16379'
$env:REDIS_PASSWORD=$taskConfig.redisPassword
$env:REDIS_NAMESPACE='yueji.test'
$env:MESSAGING_NAMESPACE='yueji.test'
$env:RABBITMQ_HOST='127.0.0.1'
$env:RABBITMQ_PORT='15673'
$env:RABBITMQ_USERNAME=$taskConfig.rabbitUsername
$env:RABBITMQ_PASSWORD=$taskConfig.rabbitPassword
$env:RABBITMQ_VHOST='yueji_test'
