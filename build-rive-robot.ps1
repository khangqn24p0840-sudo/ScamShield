$ErrorActionPreference = "Stop"

$repoRoot = $PSScriptRoot
$riveProject = Join-Path $repoRoot "design/rive/scamshield_robot"
$expectedAsset = Join-Path $riveProject "build/scamshield_robot.riv"
$rawDirectory = Join-Path $repoRoot "app/src/main/res/raw"
$destination = Join-Path $rawDirectory "scamshield_robot.riv"

$riveCommand = Get-Command "rive" -ErrorAction SilentlyContinue
if (-not $riveCommand) {
    throw "Không tìm thấy Rive CLI. Hãy cài bằng: irm https://releases.rive.app/cli/install.ps1 | iex"
}

if (-not (Test-Path $riveProject)) {
    throw "Không tìm thấy thư mục Rive: $riveProject"
}

if (Test-Path $expectedAsset) {
    Remove-Item $expectedAsset -Force
}

Write-Host "Đang mở Rive CLI preview và biên dịch robot ScamShield..."
$process = Start-Process -FilePath $riveCommand.Source -ArgumentList @($riveProject) -PassThru

try {
    $built = $false
    for ($i = 0; $i -lt 120; $i++) {
        Start-Sleep -Seconds 1
        if ((Test-Path $expectedAsset) -and (Get-Item $expectedAsset).Length -gt 0) {
            $built = $true
            break
        }
        if ($process.HasExited) {
            break
        }
    }

    if (-not $built) {
        throw "Rive CLI chưa tạo được $expectedAsset. Hãy kiểm tra lỗi trong cửa sổ preview."
    }

    New-Item -ItemType Directory -Path $rawDirectory -Force | Out-Null
    Copy-Item $expectedAsset $destination -Force
    Write-Host "Đã tạo tài nguyên: $destination"
    Write-Host "Tiếp theo hãy Build APK trong Android Studio hoặc chạy workflow GitHub Actions."
}
finally {
    if (-not $process.HasExited) {
        Stop-Process -Id $process.Id -Force
    }
}
