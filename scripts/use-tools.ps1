# 仅设置当前 PowerShell 会话，不修改系统默认工具链。
$projectRoot = Split-Path -Parent $PSScriptRoot
$frozen = Get-Content -LiteralPath (Join-Path $projectRoot 'infra/versions.lock.json') -Raw | ConvertFrom-Json
$javaDirectory = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools/java') -Directory | Select-Object -First 1
$nodeDirectory = Join-Path $projectRoot ('.tools/node/node-v' + $frozen.node.version + '-win-x64')
$mavenDirectory = Join-Path $projectRoot ('.tools/maven/apache-maven-' + $frozen.maven.version)
if (-not $javaDirectory -or -not (Test-Path -LiteralPath $nodeDirectory) -or -not (Test-Path -LiteralPath $mavenDirectory)) {
    throw '请先运行 scripts/bootstrap-tools.ps1 准备锁定的本地工具链'
}
$env:JAVA_HOME = $javaDirectory.FullName
$env:MAVEN_USER_HOME = Join-Path $projectRoot '.tools/maven-user'
$env:npm_config_cache = Join-Path $projectRoot '.tools/npm-cache'
$env:PATH = "$($javaDirectory.FullName)\bin;$nodeDirectory;$mavenDirectory\bin;$env:PATH"
$nodeExecutable = Join-Path $nodeDirectory 'node.exe'
$pnpmEntry = Join-Path $projectRoot '.tools/package-manager/node_modules/pnpm/bin/pnpm.cjs'
Set-Item -Path Function:global:pnpm -Value ({ & $nodeExecutable $pnpmEntry @args }.GetNewClosure())
