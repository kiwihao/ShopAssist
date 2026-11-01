param([string]$BaseUrl = 'http://127.0.0.1:8081')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'http-client.ps1')
$demoRoot = Split-Path $PSScriptRoot -Parent
$cases = @(
    @{name='A-size'; product='BP-A'; question='电脑仓的尺寸是多少？'; status='ANSWER'; pattern='36.*25.*3'},
    @{name='B-size'; product='BP-B'; question='电脑仓的尺寸是多少？'; status='ANSWER'; pattern='32.*23.*2'},
    @{name='A-wash'; product='BP-A'; question='可以机洗吗？应该怎么清洁？'; status='ANSWER'; pattern='(禁止|不能|不可|不建议|不支持|不要|不得).*机洗|机洗.*(禁止|不能|不可|不支持)'},
    @{name='B-wash'; product='BP-B'; question='可以机洗吗？应该怎么清洁？'; status='ANSWER'; pattern='(禁止|不能|不可|不建议|不支持|不要|不得).*机洗|机洗.*(禁止|不能|不可|不支持)'},
    @{name='unknown-load'; product='BP-A'; question='这款背包最大能承重多少公斤？'; status='INSUFFICIENT'; pattern='未|没有|无法|不详|不足|不能'},
    @{name='clarify-laptop'; product='BP-A'; question='能放下我的电脑吗？'; status='NEED_INFO'; pattern='尺寸|长.*宽.*厚'},
    @{name='missing-product'; product='BP-NOT-EXIST'; question='电脑仓尺寸是多少？'; http=404}
)
$reports = @()
foreach ($case in $cases) {
    $body = @{productId=$case.product; question=$case.question} | ConvertTo-Json
    $response = Invoke-DemoRequest -Uri "$BaseUrl/api/qa" -Method POST -Json $body
    $answer = $response.Content | ConvertFrom-Json
    $checks = @()
    if ($case.http) {
        $checks += $response.StatusCode -eq $case.http
    } else {
        $checks += $response.StatusCode -eq 200
        $checks += $answer.status -eq $case.status
        $checks += $answer.answer -match $case.pattern
    }
    $retrieved = $null
    if ($response.StatusCode -eq 200) {
        $searchBody = @{productId=$case.product; query=$case.question; topK=5} | ConvertTo-Json
        $retrieved = Invoke-DemoJson -Uri "$BaseUrl/api/retrieval/search" -Method POST -Json $searchBody
        foreach ($citation in $answer.citations) {
            $matching = @($retrieved.matches | Where-Object {
                $_.chunk_id -eq $citation.chunk_id -and $_.content -ceq $citation.content -and
                $_.product_id -eq $case.product -and $_.filename -eq $citation.filename -and
                $_.version -eq $citation.version -and $_.heading -eq $citation.heading -and
                $_.line_start -eq $citation.line_start -and $_.line_end -eq $citation.line_end
            })
            $checks += $matching.Count -eq 1
        }
        if ($answer.status -eq 'ANSWER') { $checks += @($answer.citations).Count -gt 0 }
    }
    $passed = @($checks | Where-Object { -not $_ }).Count -eq 0
    $reports += [pscustomobject]@{case=$case.name; passed=$passed; request=($body | ConvertFrom-Json); http=$response.StatusCode; response=$answer; retrieval=$retrieved}
    Write-Host "$($case.name): passed=$passed HTTP=$($response.StatusCode) status=$($answer.status)"
    Write-Host $answer.answer
}
New-Item -ItemType Directory -Force -Path (Join-Path $demoRoot '.runtime') | Out-Null
Write-DemoUtf8 -Path (Join-Path $demoRoot '.runtime/qa-results.json') -Text ($reports | ConvertTo-Json -Depth 15)
if (@($reports | Where-Object { -not $_.passed }).Count -gt 0) { throw 'Some checks failed. Inspect .runtime/qa-results.json; do not treat this run as passed.' }
Write-Host 'PASS: seven live cases and citation field checks. Manually review semantics; this is not a comprehensive quality evaluation.'
