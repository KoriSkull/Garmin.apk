# Find potential arrow images from Yandex Maps
# Copies square PNG/WEBP files (30-200px) to output folder

$sourceDir = "C:\Users\mts88\Documents\GHUD\ghud-lite\src\ru.yandex.yandexmaps_738726680_rs\res"
$outputDir = "C:\Users\mts88\Documents\GHUD\Garmin.apk\ghud-lite\yandex_arrows"

New-Item -ItemType Directory -Force -Path $outputDir | Out-Null

Write-Host "Searching in: $sourceDir"
Write-Host "Output to: $outputDir"
Write-Host ""

Add-Type -AssemblyName System.Drawing

$count = 0
$copied = 0

# Get PNG and WEBP files directly from res folder
$files = Get-ChildItem -Path $sourceDir -File | Where-Object { $_.Extension -eq '.png' -or $_.Extension -eq '.webp' }

Write-Host "Found $($files.Count) image files"
Write-Host ""

foreach ($file in $files) {
    $count++
    
    try {
        $img = [System.Drawing.Image]::FromFile($file.FullName)
        $w = $img.Width
        $h = $img.Height
        $img.Dispose()
        
        # Filter: 30-200px, roughly square
        if ($w -ge 30 -and $w -le 200 -and $h -ge 30 -and $h -le 200) {
            $ratio = $w / $h
            
            if ($ratio -ge 0.8 -and $ratio -le 1.2) {
                $newName = "{0:D3}_{1}x{2}_{3}" -f $copied, $w, $h, $file.Name
                Copy-Item $file.FullName -Destination (Join-Path $outputDir $newName)
                Write-Host "OK: $($file.Name) - ${w}x${h}px"
                $copied++
            }
        }
    }
    catch {
        # Skip errors (corrupted or unsupported images)
    }
    
    if ($count % 100 -eq 0) {
        Write-Host "Processed: $count files..."
    }
}

Write-Host ""
Write-Host "Done! Processed: $count files"
Write-Host "Copied: $copied potential arrows"
Write-Host ""

# Open output folder
explorer $outputDir
