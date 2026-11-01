# Shared HTTP helpers for Windows PowerShell 5.1 and PowerShell 7.
Add-Type -AssemblyName System.Net.Http

function Invoke-DemoRequest {
    param(
        [Parameter(Mandatory=$true)][string]$Uri,
        [string]$Method = 'GET',
        [string]$Json,
        [hashtable]$Fields,
        [string]$FilePath
    )
    $handler = [System.Net.Http.HttpClientHandler]::new()
    if (([Uri]$Uri).IsLoopback) { $handler.UseProxy = $false }
    $client = [System.Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(90)
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), $Uri)
    $response = $null
    try {
        if ($FilePath) {
            $multipart = [System.Net.Http.MultipartFormDataContent]::new()
            $request.Content = $multipart
            foreach ($field in $Fields.GetEnumerator()) {
                $multipart.Add([System.Net.Http.StringContent]::new([string]$field.Value), [string]$field.Key)
            }
            $resolvedFile = (Get-Item -LiteralPath $FilePath).FullName
            $fileContent = [System.Net.Http.ByteArrayContent]::new([IO.File]::ReadAllBytes($resolvedFile))
            $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new('application/octet-stream')
            $multipart.Add($fileContent, 'file', [IO.Path]::GetFileName($resolvedFile))
        } elseif ($PSBoundParameters.ContainsKey('Json')) {
            $request.Content = [System.Net.Http.StringContent]::new($Json, [Text.Encoding]::UTF8, 'application/json')
        }
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        return [pscustomobject]@{StatusCode=[int]$response.StatusCode; Content=$content}
    } finally {
        if ($null -ne $response) { $response.Dispose() }
        $request.Dispose()
        $client.Dispose()
    }
}

function Invoke-DemoJson {
    param([string]$Uri, [string]$Method = 'GET', [string]$Json)
    $arguments = @{Uri=$Uri; Method=$Method}
    if ($PSBoundParameters.ContainsKey('Json')) { $arguments.Json = $Json }
    $result = Invoke-DemoRequest @arguments
    if ($result.StatusCode -lt 200 -or $result.StatusCode -ge 300) {
        throw "HTTP $($result.StatusCode): $($result.Content)"
    }
    return ($result.Content | ConvertFrom-Json)
}

function Write-DemoUtf8 {
    param([string]$Path, [string]$Text)
    [IO.File]::WriteAllText($Path, $Text, [Text.UTF8Encoding]::new($false))
}
