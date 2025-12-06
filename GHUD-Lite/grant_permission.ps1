# Grant screenshot permission to Accessibility Service

$adbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

Write-Host "Granting screenshot permission..." -ForegroundColor Yellow

# Enable accessibility service
& $adbPath shell settings put secure enabled_accessibility_services iMel9i.garminhud.lite/.NavigationAccessibilityService
& $adbPath shell settings put secure accessibility_enabled 1

Write-Host ""
Write-Host "Done! Now restart the Accessibility Service:" -ForegroundColor Green
Write-Host "1. Settings -> Accessibility -> GHUD Lite -> Turn OFF" -ForegroundColor Yellow
Write-Host "2. Turn it back ON" -ForegroundColor Yellow
Write-Host "3. Try navigation again" -ForegroundColor Yellow
