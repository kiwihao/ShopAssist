param([switch]$ContainerBackend)
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
docker info --format '{{.ServerVersion}}'
if($LASTEXITCODE -ne 0){throw 'Start Docker Desktop and wait for the Linux engine before retrying.'}
docker compose up -d --wait postgres ollama
if($LASTEXITCODE -ne 0){throw 'Failed to start database or Ollama.'}
docker compose run --rm model-init
if($LASTEXITCODE -ne 0){throw 'Model initialization failed. Check network and retry; downloaded layers are retained.'}
if($ContainerBackend){
    docker compose --profile app up -d --build backend
    if($LASTEXITCODE -ne 0){throw 'Backend build/start failed.'}
}else{
    Write-Host 'Dependencies and model are ready. Restart DemoApplication in IDEA.'
}
