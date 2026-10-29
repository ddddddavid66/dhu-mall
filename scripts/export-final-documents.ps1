# 使用独立Word实例导出本轮三个文档；不访问用户已打开的文档。
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$inputDirectory = Join-Path $projectRoot 'deliverables/最终提交'
$outputDirectory = Join-Path $projectRoot '.artifacts/final-doc-qa'
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
$wordApp = New-Object -ComObject Word.Application
$wordApp.Visible = $false
$wordApp.DisplayAlerts = 0
$wordApp.AutomationSecurity = 3
try {
    foreach ($name in @('企业级需求文档.docx', '设计文档.docx', '测试报告.docx')) {
        $sourcePath = Join-Path $inputDirectory $name
        $document = $wordApp.Documents.Open($sourcePath, $false, $true)
        try {
            $targetPath = Join-Path $outputDirectory ([IO.Path]::GetFileNameWithoutExtension($name) + '.pdf')
            $document.ExportAsFixedFormat($targetPath, 17)
            Write-Output "Rendered: $name"
        } finally { $document.Close(0) }
    }
} finally { $wordApp.Quit() }
