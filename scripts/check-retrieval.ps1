param(
    [string]$BaseUrl = 'http://127.0.0.1:8081',
    [switch]$RetryFailed
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'http-client.ps1')
$demoRoot = Split-Path $PSScriptRoot -Parent
function Wait-Status([string]$JobId, [string[]]$Accepted) {
    for ($attempt=0; $attempt -lt 360; $attempt++) {
        $job = Invoke-DemoJson "$BaseUrl/api/admin/ingestion-jobs/$JobId"
        if ($job.status -in $Accepted) { return $job }
        if ($job.status -in @('FAILED','INDEX_FAILED')) { throw "Task failed: $($job.status) / $($job.error_code)" }
        Start-Sleep -Milliseconds 500
    }
    throw "Task not finished; inspect status before retrying: $JobId"
}
$reports = @()
foreach ($product in @('BP-A','BP-B')) {
    $uploadResponse = Invoke-DemoRequest -Uri "$BaseUrl/api/admin/documents" -Method POST `
        -Fields @{productId=$product; version='1'} `
        -FilePath (Join-Path $demoRoot "data\demo\documents\$product-v1.md")
    if ($uploadResponse.StatusCode -ne 202) { throw "Upload failed: HTTP $($uploadResponse.StatusCode) $($uploadResponse.Content)" }
    $upload = $uploadResponse.Content | ConvertFrom-Json
    $initialJob = Wait-Status $upload.jobId @('PARSED','READY','INDEX_FAILED')
    if ($initialJob.status -eq 'INDEX_FAILED' -and -not $RetryFailed) {
        throw "Existing task failed: $($initialJob.error_code). After fixing the cause, rerun with -RetryFailed."
    }
    $null = Invoke-DemoJson -Uri "$BaseUrl/api/admin/ingestion-jobs/$($upload.jobId)/index" -Method POST
    $job = Wait-Status $upload.jobId @('READY')
    Write-Host "$product : vector indexing READY, chunks=$($job.chunk_count)"
    $body = @{productId=$product; query='电脑仓的尺寸是多少？'; topK=3} | ConvertTo-Json
    $report = Invoke-DemoJson -Uri "$BaseUrl/api/retrieval/search" -Method POST -Json $body
    if ($report.matches.Count -eq 0) { throw 'No retrievable evidence; check active version and embedding configuration.' }
    foreach ($match in $report.matches) {
        if ($match.product_id -ne $product) { throw 'Product isolation failed.' }
    }
    $reports += $report
    $report.matches | Format-Table product_id,version,heading,similarity
}
New-Item -ItemType Directory -Force -Path (Join-Path $demoRoot '.runtime') | Out-Null
Write-DemoUtf8 -Path (Join-Path $demoRoot '.runtime\retrieval-results.json') -Text ($reports | ConvertTo-Json -Depth 8)
Write-Host 'PASS: actual model returned vectors, indexing completed, and search returned evidence within each product.'
Write-Host 'Review relevance manually; this check does not measure recall or answer quality.'
