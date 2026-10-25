$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$projectRoot = Split-Path -Parent $PSScriptRoot
$frozen = Get-Content -LiteralPath (Join-Path $projectRoot 'infra/versions.lock.json') -Raw | ConvertFrom-Json
New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot '.tools') | Out-Null

function Install-Archive($name, $url, $hash, $algorithm, $destination) {
    if (Test-Path -LiteralPath $destination) { return }
    $archive = Join-Path $projectRoot ".tools/$name.zip"
    if (-not (Test-Path -LiteralPath $archive)) { Invoke-WebRequest -Uri $url -OutFile $archive -TimeoutSec 300 }
    if ((Get-FileHash -LiteralPath $archive -Algorithm $algorithm).Hash.ToLowerInvariant() -ne $hash) {
        throw "$name 校验失败；保留文件供诊断，不执行解压"
    }
    Expand-Archive -LiteralPath $archive -DestinationPath $destination
}

Install-Archive 'jdk21' $frozen.java.url $frozen.java.sha256 'SHA256' (Join-Path $projectRoot '.tools/java')
Install-Archive 'node24' $frozen.node.url $frozen.node.sha256 'SHA256' (Join-Path $projectRoot '.tools/node')
Install-Archive 'maven' $frozen.maven.url $frozen.maven.sha512 'SHA512' (Join-Path $projectRoot '.tools/maven')
. (Join-Path $PSScriptRoot 'use-tools.ps1')
$pmDirectory = Join-Path $projectRoot '.tools/package-manager'
$pmVersion = $frozen.frontend.pnpm.version
& npm.cmd install --prefix $pmDirectory "pnpm@$pmVersion" --no-package-lock --ignore-scripts --no-audit --no-fund
if ($LASTEXITCODE -ne 0) { throw 'pnpm 安装失败' }
Write-Output '工具链已在项目 .tools 内准备完成；新会话请先点源 scripts/use-tools.ps1'
