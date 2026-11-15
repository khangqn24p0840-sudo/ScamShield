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

Write-Host "Đang kiểm tra và biên dịch robot ScamShield bằng Rive CLI..."
Write-Host "Lưu ý: Rive yêu cầu đăng nhập trước khi xuất file .riv. Chạy 'rive login' một lần nếu chưa đăng nhập."
& $riveCommand.Source $riveProject --verify
if ($LASTEXITCODE -ne 0) {
    throw "Rive source chưa hợp lệ. Hãy xử lý lỗi CLI trước khi build APK."
}

& $riveCommand.Source $riveProject --publish=local
if ($LASTEXITCODE -ne 0) {
    throw "Không xuất được file .riv. Hãy chạy 'rive login' rồi chạy lại script."
}
if (-not (Test-Path $expectedAsset) -or (Get-Item $expectedAsset).Length -eq 0) {
    throw "Không tìm thấy file Rive đã biên dịch: $expectedAsset"
}

New-Item -ItemType Directory -Path $rawDirectory -Force | Out-Null
Copy-Item $expectedAsset $destination -Force
Write-Host "Đã tạo tài nguyên: $destination"
Write-Host "Tiếp theo hãy Build APK trong Android Studio hoặc chạy GitHub Actions."
