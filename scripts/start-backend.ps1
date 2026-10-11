param([string[]]$KeyFile = @(), [string]$RoutesFile = '', [switch]$NoBuild)
$ErrorActionPreference = 'Stop'
$goldRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
foreach ($goldFile in $KeyFile) {
    $goldKeyPath = (Resolve-Path -LiteralPath $goldFile).Path
    foreach ($goldLine in [IO.File]::ReadAllLines($goldKeyPath)) {
        if ($goldLine -match '^\s*(ANY2API_[A-Z0-9_]+|GOLD_[A-Z0-9_]+)\s*=(.*)$') {
            $goldName = $Matches[1]; $goldValue = $Matches[2].Trim().Trim('"').Trim("'")
            [Environment]::SetEnvironmentVariable($goldName, $goldValue, 'Process')
        }
    }
}
if (!$RoutesFile) { $RoutesFile = Join-Path $goldRoot 'config/ai-routes.balanced.example.json' }
$env:GOLD_AI_ROUTES_JSON = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $RoutesFile).Path)
if (!$env:GOLD_AI_SHORT_ROUTES_JSON) { $env:GOLD_AI_SHORT_ROUTES_JSON = [IO.File]::ReadAllText((Join-Path $goldRoot 'config/ai-routes.fast.example.json')) }
if (!$env:GOLD_AI_LONG_ROUTES_JSON) { $env:GOLD_AI_LONG_ROUTES_JSON = [IO.File]::ReadAllText((Join-Path $goldRoot 'config/ai-routes.deep.example.json')) }
if (!$env:GOLD_AI_FALLBACK_ROUTES_JSON) { $env:GOLD_AI_FALLBACK_ROUTES_JSON = [IO.File]::ReadAllText((Join-Path $goldRoot 'config/ai-routes.fallback.example.json')) }
if (!$env:GOLD_AI_COMPRESSION_ROUTE_JSON) { $env:GOLD_AI_COMPRESSION_ROUTE_JSON = [IO.File]::ReadAllText((Join-Path $goldRoot 'config/ai-compression.mimo.example.json')) }
$goldData = Join-Path $goldRoot 'data'
New-Item -ItemType Directory -Path $goldData -Force | Out-Null
if (!$env:GOLD_AUTH_USERNAME -or !$env:GOLD_AUTH_PASSWORD) {
    $goldAccessPath = Join-Path $goldData 'local-access.json'
    if (Test-Path -LiteralPath $goldAccessPath) { $goldAccess = Get-Content -LiteralPath $goldAccessPath -Raw | ConvertFrom-Json }
    else {
        $goldAccess = @{ username = 'admin'; password = [Guid]::NewGuid().ToString('N') }
        [IO.File]::WriteAllText($goldAccessPath, ($goldAccess | ConvertTo-Json))
    }
    $env:GOLD_AUTH_USERNAME = $goldAccess.username; $env:GOLD_AUTH_PASSWORD = $goldAccess.password
    Write-Host "Local login is saved in $goldAccessPath (ignored by Git)."
}
$env:SERVER_ADDRESS = '127.0.0.1'
if (!$env:GOLD_MARKET_ENABLED) { $env:GOLD_MARKET_ENABLED = 'true' }
if (!$env:GOLD_NEWS_ENABLED) { $env:GOLD_NEWS_ENABLED = 'true' }
# Unset fee override uses the public Gate XAU_USDT contract taker fee.
if (!$env:GOLD_DB_URL) { $env:GOLD_DB_URL = 'jdbc:h2:file:' + ($goldData -replace '\\','/') + '/gold-catcher;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH' }
Push-Location (Join-Path $goldRoot 'backend')
try {
    if (!$NoBuild) { & .\gradlew.bat bootJar; if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' } }
    & java -jar build/libs/gold-catcher-server-0.4.3.jar
    if ($LASTEXITCODE -ne 0) { throw 'Backend process failed.' }
} finally { Pop-Location }
