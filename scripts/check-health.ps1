param([string]$BaseUrl = 'http://127.0.0.1:8081')
$ErrorActionPreference = 'Stop'
$result = Invoke-RestMethod -Uri "$BaseUrl/api/health" -TimeoutSec 10
if ($result.status -ne 'UP' -or $result.database -ne 'UP' -or
    [string]::IsNullOrWhiteSpace($result.pgvector) -or $result.pgvector -in @('UNKNOWN', 'MISSING')) {
    throw 'Health verification failed.'
}
$result | ConvertTo-Json
Write-Host 'PASS: service, database and pgvector are available.'
