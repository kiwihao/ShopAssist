param([string]$BaseUrl='http://127.0.0.1:8081')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'http-client.ps1')
$reports=@()
function Send-Ticket([string]$Path,[string]$Method,[hashtable]$Body,[hashtable]$Headers=@{}) {
    Invoke-DemoRequest -Uri "$BaseUrl$Path" -Method $Method -Json ($Body|ConvertTo-Json) -Headers $Headers
}
function Check-Ticket([string]$Name,[bool]$Passed) {
    if(-not $Passed){throw "FAIL: $Name"}
    Write-Host "PASS: $Name"
}
$d=Invoke-DemoJson -Uri "$BaseUrl/api/ticket-drafts" -Method POST -Json (@{productId='BP-A';summary='演示验收：拉链无法拉合，请协助核实。'}|ConvertTo-Json)
$key=[guid]::NewGuid().ToString()
$old=@{version=$d.version;confirmationToken=$d.confirmationToken;confirmed=$false}
$rejected=Send-Ticket "/api/ticket-drafts/$($d.draft_id)/confirm" 'POST' $old @{'Idempotency-Key'=$key}
Check-Ticket 'explicit-confirmation-required' ($rejected.StatusCode -eq 400)
$updated=Invoke-DemoJson -Uri "$BaseUrl/api/ticket-drafts/$($d.draft_id)" -Method PUT -Json (@{version=$d.version;confirmationToken=$d.confirmationToken;summary='演示验收：已补充问题描述，请人工核实拉链。'}|ConvertTo-Json)
$old.confirmed=$true
$stale=Send-Ticket "/api/ticket-drafts/$($d.draft_id)/confirm" 'POST' $old @{'Idempotency-Key'=$key}
Check-Ticket 'old-version-rejected' ($stale.StatusCode -eq 409)
$confirmation=@{version=$updated.version;confirmationToken=$updated.confirmationToken;confirmed=$true}
$first=Send-Ticket "/api/ticket-drafts/$($d.draft_id)/confirm" 'POST' $confirmation @{'Idempotency-Key'=$key}
Check-Ticket 'created-current-version' ($first.StatusCode -eq 200)
$ticket=$first.Content|ConvertFrom-Json
$replay=Send-Ticket "/api/ticket-drafts/$($d.draft_id)/confirm" 'POST' $confirmation @{'Idempotency-Key'=$key}
$again=$replay.Content|ConvertFrom-Json
Check-Ticket 'same-key-returns-same-ticket' ($replay.StatusCode -eq 200 -and $again.ticket_id -eq $ticket.ticket_id -and $again.replayed)
$other=Invoke-DemoJson -Uri "$BaseUrl/api/ticket-drafts" -Method POST -Json (@{productId='BP-B';summary='另一份演示草稿，仅用于验证幂等键冲突并取消。'}|ConvertTo-Json)
$otherBody=@{version=$other.version;confirmationToken=$other.confirmationToken;confirmed=$true}
$conflict=Send-Ticket "/api/ticket-drafts/$($other.draft_id)/confirm" 'POST' $otherBody @{'Idempotency-Key'=$key}
Check-Ticket 'same-key-other-draft-rejected' ($conflict.StatusCode -eq 409)
$null=Send-Ticket "/api/ticket-drafts/$($other.draft_id)/cancel" 'POST' $otherBody
$cancelled=Send-Ticket "/api/ticket-drafts/$($other.draft_id)/confirm" 'POST' $otherBody @{'Idempotency-Key'=[guid]::NewGuid().ToString()}
Check-Ticket 'cancelled-draft-cannot-create-ticket' ($cancelled.StatusCode -eq 409)
$root=Split-Path $PSScriptRoot -Parent
New-Item -ItemType Directory -Force (Join-Path $root '.runtime') | Out-Null
Write-DemoUtf8 -Path (Join-Path $root '.runtime/ticket-results.json') -Text (@{passed=$true;ticket=$ticket;replayed=$again;checks=6}|ConvertTo-Json -Depth 8)
Write-Host 'Created one local demonstration ticket; second draft cancelled. No confirmation tokens are saved to the report.'
