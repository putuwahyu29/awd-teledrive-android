# Awd TeleDrive Interactive Release Script (PowerShell)

$gradleFile = "app/build.gradle.kts"

# 1. Read current version info
$content = Get-Content $gradleFile -Raw
$currentCode = [regex]::Match($content, "versionCode\s*=\s*(\d+)").Groups[1].Value
$currentName = [regex]::Match($content, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value

Write-Host "--- TeleDrive Release Manager ---" -ForegroundColor Cyan
Write-Host "Current Version Name: $currentName"
Write-Host "Current Version Code: $currentCode"
Write-Host "---------------------------------"

# 2. Ask for new version
$newName = Read-Host "Enter New Version Name (e.g. 1.2.0)"
if ([string]::IsNullOrWhiteSpace($newName)) { $newName = $currentName }

$newCode = Read-Host "Enter New Version Code (e.g. 3)"
if ([string]::IsNullOrWhiteSpace($newCode)) { $newCode = [int]$currentCode + 1 }

Write-Host "`nUpdating to Version $newName ($newCode)..." -ForegroundColor Yellow

# 3. Update build.gradle.kts
$newContent = $content -replace "versionCode\s*=\s*\d+", "versionCode = $newCode"
$newContent = $newContent -replace 'versionName\s*=\s*"[^"]+"', "versionName = `"$newName`""
$newContent | Set-Content $gradleFile

Write-Host "Successfully updated app/build.gradle.kts`n" -ForegroundColor Green

# 4. Git Automation
$confirmGit = Read-Host "Commit, Tag, and Push to GitHub? (y/n)"
if ($confirmGit -eq 'y') {
    git add $gradleFile
    git commit -m "chore: bump version to $newName ($newCode)"

    $tagName = "v$newName"
    Write-Host "Creating tag $tagName..." -ForegroundColor Cyan
    git tag -a $tagName -m "Release $tagName"

    Write-Host "Pushing to GitHub..." -ForegroundColor Cyan
    git push origin main
    git push origin $tagName

    Write-Host "`nDone! GitHub Actions will now build the release APK." -ForegroundColor Green
} else {
    Write-Host "Skipped Git commands. Remember to commit and tag manualy if needed." -ForegroundColor Gray
}

Pause
