# Скрипт для поиска Resource ID стрелки в Яндекс.Картах

$adbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

if (-not (Test-Path $adbPath)) {
    Write-Host "Error: ADB not found!" -ForegroundColor Red
    exit 1
}

Write-Host "==================================" -ForegroundColor Cyan
Write-Host "  Finding Arrow Resource ID" -ForegroundColor Cyan
Write-Host "==================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Instructions:" -ForegroundColor Yellow
Write-Host "1. Open Yandex Maps on your phone" -ForegroundColor Yellow
Write-Host "2. Start navigation (any route)" -ForegroundColor Yellow
Write-Host "3. Wait for arrow to appear on screen" -ForegroundColor Yellow
Write-Host "4. Press Enter here" -ForegroundColor Yellow
Write-Host ""

Read-Host "Press Enter when arrow is visible on screen"

Write-Host ""
Write-Host "Dumping UI hierarchy..." -ForegroundColor Yellow

# Dump UI hierarchy
& $adbPath shell uiautomator dump /sdcard/window_dump.xml

# Pull the file
& $adbPath pull /sdcard/window_dump.xml ./window_dump.xml

if (Test-Path ./window_dump.xml) {
    Write-Host ""
    Write-Host "Success! Analyzing..." -ForegroundColor Green
    Write-Host ""
    
    # Read and parse XML
    [xml]$xml = Get-Content ./window_dump.xml
    
    # Find ImageView elements
    $imageViews = $xml.SelectNodes("//node[@class='android.widget.ImageView']")
    
    Write-Host "Found $($imageViews.Count) ImageView elements:" -ForegroundColor Cyan
    Write-Host ""
    
    foreach ($node in $imageViews) {
        $resourceId = $node.'resource-id'
        $bounds = $node.bounds
        $package = $node.package
        
        if ($package -eq "ru.yandex.yandexmaps" -and $resourceId) {
            Write-Host "Resource ID: $resourceId" -ForegroundColor Green
            Write-Host "  Bounds: $bounds" -ForegroundColor Gray
            Write-Host ""
        }
    }
    
    Write-Host ""
    Write-Host "Full dump saved to: window_dump.xml" -ForegroundColor Yellow
    Write-Host "You can open it in a text editor to inspect" -ForegroundColor Yellow
}
else {
    Write-Host "Error: Failed to get UI dump" -ForegroundColor Red
}
