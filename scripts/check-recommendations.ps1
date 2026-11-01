param([string]$BaseUrl = 'http://127.0.0.1:8081', [switch]$IncludeModel)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'http-client.ps1')
$demoRoot = Split-Path $PSScriptRoot -Parent
$cases = @(
    @{name='budget-150'; criteria=@{maxPriceCny=150}; status='CANDIDATES'; ids=@('BP-B')},
    @{name='budget-boundary-inclusive'; criteria=@{maxPriceCny=129}; status='CANDIDATES'; ids=@('BP-B')},
    @{name='budget-boundary-exclusive'; criteria=@{maxPriceCny=129;maxPriceInclusive=$false}; status='NO_MATCH'; ids=@()},
    @{name='large-laptop'; criteria=@{maxPriceCny=200;laptop=@{lengthCm=35;widthCm=24;thicknessCm=2}}; status='CANDIDATES'; ids=@('BP-A')},
    @{name='budget-size-conflict'; criteria=@{maxPriceCny=150;laptop=@{lengthCm=35;widthCm=24;thicknessCm=2}}; status='NO_MATCH'; ids=@()},
    @{name='missing-laptop-size'; criteria=@{maxPriceCny=200;needsLaptop=$true}; status='NEED_INFO'; ids=@()},
    @{name='machine-wash-required'; criteria=@{maxPriceCny=200;machineWashRequired=$true}; status='NO_MATCH'; ids=@()},
    @{name='unknown-waterproof'; criteria=@{maxPriceCny=200;unsupportedRequirements=@('必须达到IPX7防水等级')}; status='INSUFFICIENT'; ids=@()}
)
$reports = @()
foreach($case in $cases) {
    $body = $case.criteria | ConvertTo-Json -Depth 5
    $response = Invoke-DemoRequest -Uri "$BaseUrl/api/recommendations" -Method POST -Json $body
    if($response.StatusCode -ne 200) { throw "HTTP $($response.StatusCode): $($response.Content). Restart DemoApplication if the new endpoint is missing." }
    $answer = $response.Content | ConvertFrom-Json
    $actual = @($answer.candidates | ForEach-Object { $_.product.product_id } | Sort-Object)
    $passed = $answer.status -eq $case.status -and ($actual -join ',') -eq (($case.ids | Sort-Object) -join ',')
    foreach($candidate in $answer.candidates) {
        foreach($citation in $candidate.citations) { if($citation.product_id -ne $candidate.product.product_id) { $passed = $false } }
    }
    $reports += [pscustomobject]@{case=$case.name; passed=$passed; request=$case.criteria; response=$answer}
    Write-Host "$($case.name): passed=$passed status=$($answer.status) candidates=$($actual -join ',')"
}
if($IncludeModel) {
    $body = @{message='预算不到150元，必须放14寸电脑，请推荐背包'} | ConvertTo-Json
    $understood = Invoke-DemoJson -Uri "$BaseUrl/api/recommendations/understand" -Method POST -Json $body
    $criteria = $understood.criteria
    $passed = $criteria.maxPriceCny -eq 150 -and $criteria.maxPriceInclusive -eq $false -and $criteria.needsLaptop -eq $true -and $null -eq $criteria.laptop
    $reports += [pscustomobject]@{case='actual-model-requirement'; passed=$passed; response=$understood}
    Write-Host "actual-model-requirement: passed=$passed"
}
New-Item -ItemType Directory -Force -Path (Join-Path $demoRoot '.runtime') | Out-Null
Write-DemoUtf8 -Path (Join-Path $demoRoot '.runtime/recommendation-results.json') -Text ($reports | ConvertTo-Json -Depth 20)
if(@($reports | Where-Object { -not $_.passed }).Count -gt 0) { throw 'Some recommendation checks failed; inspect .runtime/recommendation-results.json.' }
Write-Host 'PASS: fixed recommendation cases. This is not a complete recommendation-quality or model-extraction evaluation.'
