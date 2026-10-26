$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
Set-Location $taskRoot
$taskCompose=@('compose','-p','yueji-local-test','--env-file','.artifacts/local-test/runtime.env','-f','infra/compose.yml','-f','infra/compose.local-test.yml')
$taskRecord=Join-Path $taskRoot '.artifacts/R-recovery'
New-Item -ItemType Directory -Force $taskRecord | Out-Null
& docker @taskCompose config | Set-Content -Encoding utf8 .artifacts/local-test/resolved-compose.yml
if($LASTEXITCODE -ne 0){throw 'Compose resolution failed'}
& docker @taskCompose stop web backend
if($LASTEXITCODE -ne 0){throw 'Could not establish local maintenance window'}
try {
    $taskName='yueji-local-recovery-'+[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    & docker run --name $taskName --network yueji-local-test_yueji --mount 'type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock' --mount "type=bind,source=$taskRoot,target=/src,readonly" --mount 'type=volume,source=yueji-local-recovery,target=/opt/yueji' --mount "type=bind,source=$taskRoot/.artifacts/local-test/files,target=/opt/yueji/files" yueji-local-recovery-tools@sha256:4bc6c0545adc96066181e81eb53e59c94ef4fa98c9ab0f1c9e3d48aef5ea7d25 *> .artifacts/R-recovery-run.log
    $taskExit=$LASTEXITCODE
    & docker cp "${taskName}:/opt/yueji/records/." $taskRecord
    if($taskExit -ne 0){throw 'Encrypted backup / isolated restore failed; inspect preserved logs'}
    $taskResult=Get-Content (Join-Path $taskRecord 'R-local-recovery.json') -Raw | ConvertFrom-Json
    $taskBackend=(Get-Content .artifacts/local-test/runtime.env | Where-Object {$_ -like 'BACKEND_IMAGE=*'}).Substring(14)
    $taskRestored='yueji-local-restored-'+[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $taskArguments=@('run','-d','--name',$taskRestored,'--network','yueji-local-test_yueji','--memory','1g','-p','127.0.0.1:18081:8080',
        '--mount',"type=volume,source=yueji-local-recovery,target=/var/lib/yueji/files,volume-subpath=$($taskResult.restoreSubpath),readonly",
        '--mount','type=volume,source=yueji-local-recovery,target=/run/secrets,volume-subpath=restore-app-secrets,readonly',
        '-e','SPRING_PROFILES_ACTIVE=test','-e','APP_BIND_ADDRESS=0.0.0.0','-e','SERVER_PORT=8080',
        '-e',"DB_URL=jdbc:mysql://mysql:3306/$($taskResult.restoreDatabase)?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        '-e','DB_USERNAME=yueji_test','-e','DB_PASSWORD_FILE=/run/secrets/database-password',
        '-e','JWT_SIGNING_KEY_FILE=/run/secrets/jwt-signing-key','-e','FILE_STORAGE_ROOT=/var/lib/yueji/files',
        '-e','PAYMENT_PROVIDER=LOCAL_MOCK','-e','ENABLE_MOCK_PAYMENT=true','-e','YUEJI_SCHEDULING_ENABLED=false',
        '-e','REDIS_ENABLED=false','-e','MESSAGING_ENABLED=false',$taskBackend)
    & docker @taskArguments | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Restored application could not start'}
    try {
        $taskReady=$false
        for($taskAttempt=0;$taskAttempt -lt 90;$taskAttempt++) {
            try {if((Invoke-RestMethod 'http://127.0.0.1:18081/actuator/health/readiness').status -eq 'UP'){$taskReady=$true;break}}catch {}
            Start-Sleep -Seconds 1
        }
        if(-not $taskReady){throw 'Restored app readiness timed out'}
        $taskFixture=Get-Content .artifacts/R-e2e-fixtures.json -Raw | ConvertFrom-Json
        $taskBuyer=(Get-Content .artifacts/R-e2e-accounts.json -Raw | ConvertFrom-Json).buyer
        $taskLogin=Invoke-RestMethod 'http://127.0.0.1:18081/api/v1/auth/login' -Method Post -ContentType 'application/json' -Body (@{username=$taskBuyer.username;password=$taskBuyer.password}|ConvertTo-Json -Compress)
        if($taskLogin.code -ne 'OK'){throw 'Restored account could not authenticate'}
        $taskOrder=Invoke-RestMethod "http://127.0.0.1:18081/api/v1/orders/$($taskFixture.completedOrder)" -Headers @{Authorization="Bearer $($taskLogin.data.accessToken)"}
        if($taskOrder.data.status -ne 'COMPLETED'){throw 'Restored completed order differs'}
        Invoke-WebRequest "http://127.0.0.1:18081/api/v1/files/$($taskFixture.coverFileId)/content" -OutFile (Join-Path $taskRecord 'restored-cover.png') | Out-Null
        $taskHash=(Get-FileHash (Join-Path $taskRecord 'restored-cover.png') -Algorithm SHA256).Hash.ToLowerInvariant()
        if($taskHash -ne $taskResult.referencedFileSha256){throw 'Restored application file bytes differ'}
        @{status='PASSED';readiness='UP';restoredLogin='OK';completedOrderStatus=$taskOrder.data.status;referencedFileSha256=$taskHash;offsiteVerified=$false;checkedAt=[DateTimeOffset]::UtcNow.ToString('o')} | ConvertTo-Json | Set-Content (Join-Path $taskRecord 'R-restored-application.json')
    } finally {& docker logs $taskRestored *> (Join-Path $taskRecord 'restored-application.log'); & docker stop $taskRestored | Out-Null}
} finally {
    & docker @taskCompose up -d --wait backend web
    if($LASTEXITCODE -ne 0){throw 'Source application restart failed; inspect local stack'}
}
