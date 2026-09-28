param(
    [string]$Manifest = "docs/testing/TEST_CLASSIFICATION.csv"
)

$ErrorActionPreference = "Stop"
$validConfidence = @("high", "reviewed")
$validCategories = @(
    "BEHAVIOR_CONTRACT",
    "ARCHITECTURE_CONTRACT",
    "IMPLEMENTATION_VERIFICATION",
    "OBSOLETE"
)

if (-not (Test-Path $Manifest)) {
    throw "Missing test classification manifest: $Manifest"
}

$pattern = [regex]::new(
    '@Test\b(?:(?!@Test).){0,1200}?\bfun\s+(`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)\s*\(',
    [System.Text.RegularExpressions.RegexOptions]::Singleline
)

$actual = New-Object 'System.Collections.Generic.HashSet[string]'
$files = git ls-files '*.kt'
foreach ($file in $files) {
    if ($file -notmatch '/src/(test|androidTest)/') { continue }
    if (-not (Test-Path $file)) { continue }
    $text = [System.IO.File]::ReadAllText((Resolve-Path $file))
    foreach ($match in $pattern.Matches($text)) {
        $name = $match.Groups[1].Value.Trim('`')
        [void]$actual.Add("$file::$name")
    }
}

$rows = Import-Csv $Manifest
$seen = New-Object 'System.Collections.Generic.HashSet[string]'
$duplicates = @()
$invalid = @()
$invalidConfidence = @()
foreach ($row in $rows) {
    $key = "$($row.file)::$($row.test)"
    if (-not $seen.Add($key)) { $duplicates += $key }
    if ($validCategories -notcontains $row.category) { $invalid += "$key -> $($row.category)" }
    if ($validConfidence -notcontains $row.confidence) { $invalidConfidence += "$key -> $($row.confidence)" }
}

$missing = @($actual | Where-Object { -not $seen.Contains($_) } | Sort-Object)
$stale = @($seen | Where-Object { -not $actual.Contains($_) } | Sort-Object)

Write-Host "Tracked @Test methods: $($actual.Count)"
Write-Host "Manifest rows:        $($rows.Count)"
$rows | Group-Object category | Sort-Object Name | ForEach-Object {
    Write-Host ("  {0,-28} {1,5}" -f $_.Name, $_.Count)
}

if ($duplicates.Count -or $invalid.Count -or $invalidConfidence.Count -or $missing.Count -or $stale.Count) {
    if ($duplicates.Count) { Write-Host "`nDuplicate rows:"; $duplicates | ForEach-Object { Write-Host "  $_" } }
    if ($invalid.Count) { Write-Host "`nInvalid categories:"; $invalid | ForEach-Object { Write-Host "  $_" } }
    if ($invalidConfidence.Count) { Write-Host ""; Write-Host "Unreviewed/invalid confidence:"; $invalidConfidence | ForEach-Object { Write-Host "  $_" } }
    if ($missing.Count) { Write-Host "`nUnclassified tests:"; $missing | ForEach-Object { Write-Host "  $_" } }
    if ($stale.Count) { Write-Host "`nStale manifest rows:"; $stale | ForEach-Object { Write-Host "  $_" } }
    exit 1
}

Write-Host "Test classification manifest is complete and internally consistent."
