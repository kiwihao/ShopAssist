param(
    [string]$BaseUrl = 'http://127.0.0.1:8081',
    [switch]$VerifySaved
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'http-client.ps1')
$demoRoot = Split-Path $PSScriptRoot -Parent
$resultPath = Join-Path $demoRoot '.runtime\ingestion-results.json'

function Assert-Equal($Actual, $Expected, [string]$Message) {
    if ($Actual -ne $Expected) { throw "$Message : expected=$Expected actual=$Actual" }
}
function Upload([string]$Product, [string]$FilePath, [int]$Version = 1) {
    Invoke-DemoRequest -Uri "$BaseUrl/api/admin/documents" -Method POST `
        -Fields @{productId=$Product; version="$Version"} -FilePath $FilePath
}
function Wait-Job([string]$JobId) {
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        $job = Invoke-DemoJson "$BaseUrl/api/admin/ingestion-jobs/$JobId"
        if ($job.status -in @('PARSED', 'FAILED', 'READY', 'INDEX_FAILED')) { return $job }
        Start-Sleep -Milliseconds 250
    }
    throw "Task timeout: $JobId"
}

$products = @(Invoke-DemoJson "$BaseUrl/api/products")
$seed = @(Import-Csv -LiteralPath (Join-Path $demoRoot 'data\demo\products.csv') -Encoding UTF8)
Assert-Equal $products.Count 2 'product count'
foreach ($row in $seed) {
    $actual = @($products | Where-Object product_id -EQ $row.product_id)[0]
    foreach ($field in @('name', 'category', 'status')) { Assert-Equal $actual.$field $row.$field $field }
    foreach ($field in @('price_cny', 'laptop_height_cm', 'laptop_width_cm', 'laptop_depth_cm')) {
        Assert-Equal ([decimal]$actual.$field) ([decimal]$row.$field) $field
    }
}

if ($VerifySaved) {
    $saved = Get-Content -LiteralPath $resultPath -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($entry in $saved) {
        $job = Wait-Job $entry.jobId
        if ($job.status -notin @('PARSED', 'READY')) { throw "Unexpected saved job status: $($job.status)" }
        $chunks = @(Invoke-DemoJson "$BaseUrl/api/admin/ingestion-jobs/$($entry.jobId)/chunks")
        Assert-Equal $chunks.Count $entry.chunkCount 'saved chunks count'
        Assert-Equal ((@($chunks.chunk_id) | Sort-Object) -join ',') ((@($entry.chunkIds) | Sort-Object) -join ',') 'saved chunk identities'
    }
    Write-Host 'PASS: persisted jobs and chunks survived restart; product seeds are unchanged.'
    return
}

New-Item -ItemType Directory -Force -Path (Join-Path $demoRoot '.runtime\fixtures') | Out-Null
$results = @()
foreach ($row in $seed) {
    $file = Join-Path (Join-Path $demoRoot 'data\demo') $row.document_file
    $response = Upload $row.product_id $file
    Assert-Equal $response.StatusCode 202 'upload status'
    $jobId = ($response.Content | ConvertFrom-Json).jobId
    $job = Wait-Job $jobId
    if ($job.status -notin @('PARSED', 'READY')) { throw "Unexpected job status: $($job.status)" }
    $chunks = @(Invoke-DemoJson "$BaseUrl/api/admin/ingestion-jobs/$jobId/chunks")
    if ($chunks.Count -lt 2) { throw 'Too few chunks' }
    foreach ($chunk in $chunks) {
        Assert-Equal $chunk.product_id $row.product_id 'chunk product ownership'
        if (-not $chunk.heading -or $chunk.line_start -lt 1 -or $chunk.line_end -lt $chunk.line_start) {
            throw 'Missing heading or source position'
        }
    }
    $repeat = Upload $row.product_id $file
    Assert-Equal ($repeat.Content | ConvertFrom-Json).jobId $jobId 'idempotent repeated upload'
    $results += [pscustomobject]@{ productId = $row.product_id; jobId = $jobId; chunkCount = $chunks.Count; chunkIds = @($chunks.chunk_id) }
}

$aFile = Join-Path $demoRoot 'data\demo\documents\BP-A-v1.md'
Assert-Equal (Upload 'UNKNOWN' $aFile).StatusCode 404 'unknown product'
Assert-Equal (Upload 'BP-B' $aFile).StatusCode 400 'mismatched product metadata'
Assert-Equal (Upload 'BP-A' $aFile 0).StatusCode 400 'invalid version'
$conflictPath = Join-Path $demoRoot '.runtime\fixtures\BP-A-v1.md'
$original = Get-Content -LiteralPath $aFile -Raw -Encoding UTF8
Write-DemoUtf8 -Path $conflictPath -Text ($original + "`n不同的内容")
Assert-Equal (Upload 'BP-A' $conflictPath).StatusCode 409 'version conflict'
$emptyPath = Join-Path $demoRoot '.runtime\fixtures\headings-only.md'
Write-DemoUtf8 -Path $emptyPath -Text ('# 仅有标题' + [Environment]::NewLine)
$emptyResponse = Upload 'BP-A' $emptyPath
Assert-Equal $emptyResponse.StatusCode 202 'headings-only upload'
$failed = Wait-Job ($emptyResponse.Content | ConvertFrom-Json).jobId
Assert-Equal $failed.status 'FAILED' 'no body content'
Assert-Equal $failed.error_code 'NO_CONTENT' 'failure code'
$failedChunks = @(Invoke-DemoJson "$BaseUrl/api/admin/ingestion-jobs/$($failed.job_id)/chunks")
Assert-Equal $failedChunks.Count 0 'failed job has no chunks'
$badPath = Join-Path $demoRoot '.runtime\fixtures\invalid.md'
[IO.File]::WriteAllBytes($badPath, [byte[]]@(0xff, 0xfe, 0x80))
Assert-Equal (Upload 'BP-A' $badPath).StatusCode 400 'invalid UTF-8'
$largePath = Join-Path $demoRoot '.runtime\fixtures\large.md'
[IO.File]::WriteAllBytes($largePath, [byte[]]::new(270000))
Assert-Equal (Upload 'BP-A' $largePath).StatusCode 413 'file size limit'
$notMarkdownPath = Join-Path $demoRoot '.runtime\fixtures\not-markdown.txt'
Write-DemoUtf8 -Path $notMarkdownPath -Text '文本'
Assert-Equal (Upload 'BP-A' $notMarkdownPath).StatusCode 400 'unsupported extension'
$unknown = Invoke-DemoRequest "$BaseUrl/api/admin/ingestion-jobs/00000000-0000-0000-0000-000000000000"
Assert-Equal $unknown.StatusCode 404 'unknown job'
Write-DemoUtf8 -Path $resultPath -Text ($results | ConvertTo-Json -Depth 5)
$results | Format-Table productId,jobId,chunkCount
Write-Host 'PASS: seeds, upload, chunk ownership, source positions, duplicate upload, conflict and failure cases.'
