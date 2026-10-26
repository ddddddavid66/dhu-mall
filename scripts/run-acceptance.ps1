$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'use-tools.ps1')
. (Join-Path $PSScriptRoot 'use-db.ps1')
. (Join-Path $PSScriptRoot 'use-dependencies.ps1')
Push-Location (Join-Path $taskRoot 'backend')
try { ./mvnw.cmd -B -ntp '-Dtest=E2EFixturePreparation' test *> ../.artifacts/R-e2e-fixture.log; if($LASTEXITCODE -ne 0){throw 'E2E夹具准备失败'} }
finally {Pop-Location}
if(Get-NetTCPConnection -State Listen -LocalPort 18080 -ErrorAction SilentlyContinue){throw '18080已占用，不停止未知进程'}
$env:DB_URL=$env:YUEJI_TEST_DB_URL
$env:DB_USERNAME=$env:YUEJI_TEST_DB_USERNAME
$env:DB_PASSWORD=$env:YUEJI_TEST_DB_PASSWORD
$env:PAYMENT_PROVIDER='LOCAL_MOCK'
$env:ENABLE_MOCK_PAYMENT='true'
$env:JWT_SIGNING_KEY=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:E2E_BASE_URL='http://127.0.0.1:5173'
$env:E2E_BACKEND_URL='http://127.0.0.1:18080'
$taskJar=Join-Path $taskRoot 'backend/target/yueji-mall-0.1.0-SNAPSHOT.jar'
$taskJava=Join-Path $env:JAVA_HOME 'bin/java.exe'
$taskProcess=Start-Process -FilePath $taskJava -ArgumentList @('-jar',$taskJar,'--spring.profiles.active=test','--server.port=18080','--yueji.security.cookie-secure=false','--yueji.allowed-origins=http://127.0.0.1:5173','--yueji.security.login-limit=1000','--yueji.security.write-limit=1000') -PassThru -WindowStyle Hidden -WorkingDirectory $taskRoot -RedirectStandardOutput (Join-Path $taskRoot '.artifacts/R-e2e-backend.log') -RedirectStandardError (Join-Path $taskRoot '.artifacts/R-e2e-backend.err.log')
try {
    $taskReady=$false
    for($taskAttempt=0;$taskAttempt -lt 60;$taskAttempt++) {
        if($taskProcess.HasExited){throw 'E2E后端提前退出'}
        try {$taskHealth=Invoke-RestMethod "$env:E2E_BACKEND_URL/actuator/health/readiness";if($taskHealth.status -eq 'UP'){$taskReady=$true;break}}catch {}
        Start-Sleep -Seconds 1
    }
    if(-not $taskReady){throw 'E2E就绪超时'}
    Push-Location $taskRoot
    try {pnpm --dir frontend run test:acceptance; if($LASTEXITCODE -ne 0){throw '真实浏览器验收失败'}} finally {Pop-Location}
} finally {if(-not $taskProcess.HasExited){Stop-Process -Id $taskProcess.Id; $taskProcess.WaitForExit()}}
