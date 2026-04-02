# ========================================================
# STEP 1: BUILDER - 负责 Gradle 编译
# ========================================================
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
$env:LC_ALL = "C.UTF-8"

$SDK_PATH  = "C:\Users\ly775\AppData\Local\Android\Sdk"
$JAVA_HOME = "D:\Program Files\Android\Android Studio\jbr"

# 注入环境变量
$env:JAVA_HOME = $JAVA_HOME
$env:ANDROID_HOME = $SDK_PATH

Clear-Host
Write-Host "--- [ PHASE: BUILD ] ---" -ForegroundColor Cyan

# 1. 检查 Gradle Wrapper 完整性
$wrapperJar = ".\gradle\wrapper\gradle-wrapper.jar"
if (-not (Test-Path $wrapperJar)) {
    Write-Error "❌ 缺失二进制包: $wrapperJar"
    Write-Host "👉 请执行: Copy-Item -Path '..\MyApplication\gradle' -Destination '.\gradle' -Recurse" -ForegroundColor Yellow
    pause; exit
}

# 2. 执行编译
Write-Host ">> Building APK..." -ForegroundColor Gray
# 使用 chcp 65001 强制 cmd 处于 UTF-8 模式，防止 javac 的输出乱码
cmd.exe /c "chcp 65001 >nul & .\gradlew.bat assembleDebug -q --console plain"

if ($LASTEXITCODE -eq 0) {
    Write-Host "`n✅ Build Successful!" -ForegroundColor Green
} else {
    Write-Error "❌ Build Failed!"
    pause; exit
}