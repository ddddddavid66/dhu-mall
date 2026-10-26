$ErrorActionPreference='Stop'
$taskProjectRoot=Split-Path -Parent $PSScriptRoot
$taskDirectory=Join-Path $taskProjectRoot '.artifacts'
$taskCredentials=Join-Path $taskDirectory 'R-dependencies.json'
New-Item -ItemType Directory -Force -Path $taskDirectory | Out-Null
if (-not (Test-Path -LiteralPath $taskCredentials)) {
    function New-TaskSecret { $bytes=New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes); [Convert]::ToBase64String($bytes) }
    $taskConfig=[ordered]@{redisPassword=(New-TaskSecret);rabbitUsername='yueji_test';rabbitPassword=(New-TaskSecret)}
    $taskConfig | ConvertTo-Json | Set-Content -LiteralPath $taskCredentials -Encoding utf8
}
$taskConfig=Get-Content -LiteralPath $taskCredentials -Raw | ConvertFrom-Json
$taskRedisPasswordFile=Join-Path $taskDirectory 'R-redis-password'
[IO.File]::WriteAllText($taskRedisPasswordFile,$taskConfig.redisPassword)
$taskEnvFile=Join-Path $taskDirectory 'R-dependencies.env'
$taskEnv=@("RABBITMQ_USERNAME=$($taskConfig.rabbitUsername)","RABBITMQ_PASSWORD=$($taskConfig.rabbitPassword)","REDIS_PASSWORD_FILE=$($taskRedisPasswordFile.Replace('\','/'))")
[IO.File]::WriteAllLines($taskEnvFile,$taskEnv)
docker compose --env-file $taskEnvFile -f (Join-Path $taskProjectRoot 'infra/compose.dependencies.yml') up -d --wait
if ($LASTEXITCODE -ne 0) { throw '隔离依赖启动失败；不清理已有volume' }
Write-Output '隔离依赖就绪；凭据仅保存在忽略目录，不会打印。'
