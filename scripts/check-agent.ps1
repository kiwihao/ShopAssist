param([string]$BaseUrl='http://127.0.0.1:8081')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'http-client.ps1')
$cases=@(
    @{name='knowledge'; productId='BP-A'; message='这款背包的电脑仓尺寸是多少？'; status='ANSWER'; tool='query_product_knowledge'},
    @{name='prepare-recommendation'; productId='BP-A'; message='预算150元以内推荐一个背包'; status='NEED_CONFIRMATION'; tool='prepare_recommendation'},
    @{name='missing-product'; productId=$null; message='这款背包能机洗吗？'; status='NEED_INFO'; tool='query_product_knowledge'},
    @{name='product-mismatch'; productId='BP-A'; message='BP-B的电脑仓尺寸是多少？'; status='NEED_INFO'; tool='query_product_knowledge'}
)
$reports=@()
foreach($case in $cases) {
    $request=@{productId=$case.productId; message=$case.message}
    $answer=Invoke-DemoJson -Uri "$BaseUrl/api/agent" -Method POST -Json ($request|ConvertTo-Json)
    $passed=$answer.status -eq $case.status -and $answer.toolCalls -le 3 -and @($answer.trace | Where-Object { $_.tool -eq $case.tool -and $_.outcome -eq 'SUCCESS' }).Count -ge 1
    if($case.name -eq 'knowledge') { $passed=$passed -and $answer.qa.answer -match '36' -and @($answer.qa.citations | Where-Object { $_.product_id -ne 'BP-A' }).Count -eq 0 -and $answer.qa.citations.Count -gt 0 }
    if($case.name -eq 'prepare-recommendation') { $passed=$passed -and $answer.criteria.maxPriceCny -eq 150 -and $null -eq $answer.candidates }
    $reports += [pscustomobject]@{case=$case.name;passed=$passed;request=$request;response=$answer}
    Write-Host "$($case.name): passed=$passed status=$($answer.status) calls=$($answer.toolCalls)"
}
$resultPath=Join-Path (Split-Path $PSScriptRoot -Parent) '.runtime/agent-results.json'
New-Item -ItemType Directory -Force (Split-Path $resultPath -Parent) | Out-Null
Write-DemoUtf8 -Path $resultPath -Text ($reports|ConvertTo-Json -Depth 20)
if(@($reports|Where-Object{-not $_.passed}).Count -gt 0) { throw 'Agent checks failed. Inspect .runtime/agent-results.json.' }
Write-Host 'PASS: native tool calling, canonical results and confirmation boundary. This is not an agent-quality benchmark.'
