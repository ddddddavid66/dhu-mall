$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'use-tools.ps1')
if (-not $env:YUEJI_TEST_DB_URL) { . (Join-Path $PSScriptRoot 'use-db.ps1') }
. (Join-Path $PSScriptRoot 'use-dependencies.ps1')
New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot '.artifacts') | Out-Null
$results = [System.Collections.Generic.List[object]]::new()

function Invoke-Check($name, [scriptblock] $action) {
    $log = Join-Path $projectRoot ".artifacts/$name.log"
    & $action *> $log
    $checkExit = $LASTEXITCODE
    $results.Add([ordered]@{ command = $name; exitCode = $checkExit; log = ".artifacts/$name.log" })
    Write-Output "$name : exit $checkExit"
    if ($checkExit -ne 0) {
        Get-Content -LiteralPath $log -Tail 40
        throw "$name 失败，停止后续检查"
    }
}

Push-Location $projectRoot
try {
    Invoke-Check 'contract-sync' { python scripts/sync-contract.py --check }
    Invoke-Check 'pnpm-frozen-install' { pnpm --dir frontend install --frozen-lockfile }
    Invoke-Check 'frontend-typecheck' { pnpm --dir frontend typecheck }
    Invoke-Check 'frontend-tests' { pnpm --dir frontend test }
    Invoke-Check 'frontend-build' { pnpm --dir frontend build }
    Invoke-Check 'contract-lint' { pnpm --dir frontend contract:lint }
    Push-Location backend
    try { Invoke-Check 'backend-verify' { ./mvnw.cmd -B -ntp clean verify } }
    finally { Pop-Location }
} finally {
    Pop-Location
    $results | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $projectRoot '.artifacts/verification-results.json') -Encoding utf8
}
