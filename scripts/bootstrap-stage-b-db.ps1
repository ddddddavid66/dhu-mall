# 本脚本只操作项目自己的 127.0.0.1:13306 实例，不访问系统 MySQL80。
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$projectRoot=Split-Path -Parent $PSScriptRoot
New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot '.tools'),(Join-Path $projectRoot '.artifacts') | Out-Null
$freeze=Get-Content (Join-Path $projectRoot 'infra/versions.stage-b.lock.json') -Raw | ConvertFrom-Json
$mysqlRoot=Join-Path $projectRoot ('.tools/mysql/mysql-'+$freeze.mysql.version+'-winx64')
$archive=Join-Path $projectRoot '.tools/mysql84.zip'
if(-not(Test-Path (Join-Path $mysqlRoot 'bin/mysqld.exe'))){
    if(-not(Test-Path $archive)){Invoke-WebRequest -Uri $freeze.mysql.url -OutFile $archive -TimeoutSec 300}
    if((Get-FileHash $archive -Algorithm MD5).Hash.ToLowerInvariant() -ne $freeze.mysql.officialMd5){throw 'MySQL 官方归档 MD5 不匹配'}
    if((Get-FileHash $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $freeze.mysql.localArchiveSha256){throw 'MySQL 冻结归档 SHA-256 不匹配'}
    Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $projectRoot '.tools/mysql') -Force
}
$dataRoot=Join-Path $projectRoot '.tools/mysql-data-stage-b'
$settingsPath=Join-Path $projectRoot '.tools/stage-b-db.json'
$mysql=Join-Path $mysqlRoot 'bin/mysql.exe'
$mysqld=Join-Path $mysqlRoot 'bin/mysqld.exe'
$fresh=-not(Test-Path (Join-Path $dataRoot 'auto.cnf'))
if($fresh){
    if(Test-Path $settingsPath){throw '已有数据库凭据但数据目录缺失，请人工检查，不自动重新初始化'}
    & $mysqld --no-defaults --initialize-insecure "--basedir=$mysqlRoot" "--datadir=$dataRoot" --console *> (Join-Path $projectRoot '.artifacts/B-mysql-init.log')
    if($LASTEXITCODE -ne 0){throw 'MySQL 初始化失败，查看 .artifacts/B-mysql-init.log'}
}
$pidPath=Join-Path $projectRoot '.artifacts/B-mysql.pid'
$owned=$false
if(Test-Path $pidPath){
    $running=Get-Process -Id ([int](Get-Content $pidPath)) -ErrorAction SilentlyContinue
    $owned=$running -and $running.Path -eq $mysqld
}
if(-not $owned){
    if(Get-NetTCPConnection -LocalPort 13306 -State Listen -ErrorAction SilentlyContinue){throw '13306 已被非本脚本确认的进程占用，拒绝连接或覆盖'}
    $arguments=@('--no-defaults',"--basedir=$mysqlRoot","--datadir=$dataRoot",'--bind-address=127.0.0.1','--port=13306','--mysqlx=0','--default-time-zone=+00:00','--character-set-server=utf8mb4','--console')
    $process=Start-Process -FilePath $mysqld -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $projectRoot '.artifacts/B-mysql.stdout.log') -RedirectStandardError (Join-Path $projectRoot '.artifacts/B-mysql.stderr.log')
    $process.Id | Set-Content $pidPath
}
for($attempt=0;$attempt -lt 40;$attempt++){
    if(Get-NetTCPConnection -LocalPort 13306 -State Listen -ErrorAction SilentlyContinue){break}
    Start-Sleep -Milliseconds 500
}
if($fresh){
    $random={ [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)) }
    $settings=@{rootPassword=(& $random);devPassword=(& $random);testPassword=(& $random)}
    $settings | ConvertTo-Json | Set-Content $settingsPath -Encoding utf8
    $env:MYSQL_PWD=''
    $sql="ALTER USER 'root'@'localhost' IDENTIFIED BY '$($settings.rootPassword)';"
    $sql | & $mysql --no-defaults --host=127.0.0.1 --port=13306 --user=root
    if($LASTEXITCODE -ne 0){throw '隔离 MySQL root 口令设置失败'}
}
$settings=Get-Content $settingsPath -Raw | ConvertFrom-Json
$env:MYSQL_PWD=$settings.rootPassword
$sql=@"
-- 仅项目隔离的开发实例：允许库级 TRIGGER 权限创建审计保护触发器，不授予应用 SUPER。
SET GLOBAL log_bin_trust_function_creators=1;
CREATE DATABASE IF NOT EXISTS yueji_mall_dev CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS yueji_mall_test CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS yueji_migration_test CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS 'yueji_dev'@'localhost' IDENTIFIED BY '$($settings.devPassword)';
CREATE USER IF NOT EXISTS 'yueji_test'@'localhost' IDENTIFIED BY '$($settings.testPassword)';
GRANT ALL PRIVILEGES ON yueji_mall_dev.* TO 'yueji_dev'@'localhost';
GRANT ALL PRIVILEGES ON yueji_mall_test.* TO 'yueji_test'@'localhost';
GRANT ALL PRIVILEGES ON yueji_migration_test.* TO 'yueji_test'@'localhost';
"@
try{
    $sql | & $mysql --no-defaults --host=127.0.0.1 --port=13306 --user=root
    if($LASTEXITCODE -ne 0){throw '隔离数据库账号和库准备失败'}
}finally{Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue}
Write-Output '项目 MySQL 8.4 就绪：127.0.0.1:13306；开发/测试库及随机凭据已隔离，未输出口令。'
