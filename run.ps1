# ========================================================
# STEP 2: RUNNER - 负责部署与执行
# ========================================================
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding = [System.Text.Encoding]::UTF8

$SDK_PATH = "C:\Users\ly775\AppData\Local\Android\Sdk"
$ADB      = "$SDK_PATH\platform-tools\adb.exe"

Write-Host "--- [ PHASE: DEPLOY & RUN ] ---" -ForegroundColor Cyan

# 1. 动态识别入口类 (从 app/src 自动提取)
Write-Host ">> Analyzing source for entry point..." -ForegroundColor Gray
$MainFile = Get-ChildItem -Path ".\app\src" -Recurse -Include *.kt, *.java | Where-Object {
    $Content = Get-Content $_.FullName
    $Content -match "fun\s+main" -or $Content -match "static\s+void\s+main"
} | Select-Object -First 1

if (-not $MainFile) { 
    Write-Error "❌ 无法定位 main 函数！请确保代码中有 fun main 或 static void main"
    pause; exit 
}

$Package = (Get-Content $MainFile.FullName | Select-String -Pattern "^package\s+([\w\.]+)").Matches.Groups[1].Value
$TargetClass = if ($MainFile.Extension -eq ".kt") { "$Package.$($MainFile.BaseName)Kt" } else { "$Package.$($MainFile.BaseName)" }

# 2. 定位生成的 APK
$ApkFile = Get-ChildItem -Path ".\app\build\outputs\apk\debug\*.apk" | Sort-Object LastWriteTime -Descending | Select-Object -First 1

if (-not $ApkFile) {
    Write-Error "❌ 未找到 APK 文件，请先运行 build.ps1"
    pause; exit
}

$RemoteApk = "/data/local/tmp/lite_runtime.apk"

# 3. 推送到手机
Write-Host ">> Deploying $($ApkFile.Name) to phone..." -ForegroundColor Gray
& $ADB shell "pkill -f app_process" 2>$null
& $ADB push $ApkFile.FullName $RemoteApk | Out-Null

# 4. 执行与日志抓取 (输出到文件)
Write-Host "----------------------------------------"
Write-Host ">> RUNNING: $TargetClass" -ForegroundColor Green
Write-Host "----------------------------------------"

# 准备日志目录和文件路径 (带时间戳，防止覆盖历史日志)
$LogDir = ".\logs"
if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir | Out-Null }
$Timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$ConsoleOutFile = "$LogDir\console_output_$Timestamp.txt"
$LogcatFile = "$LogDir\logcat_$Timestamp.txt"
$FilteredLogcatFile = "$LogDir\logcat_zero_recorder_$Timestamp.txt"

Write-Host ">> Console log path: $ConsoleOutFile" -ForegroundColor DarkGray
Write-Host ">> Logcat path: $LogcatFile" -ForegroundColor DarkGray
Write-Host ">> Filtered logcat path: $FilteredLogcatFile" -ForegroundColor DarkGray

# 清除旧日志
& $ADB logcat -c

# 执行程序
# 💡 [关键改动]: 使用 2>&1 捕获所有输出(含错误)，并通过 Tee-Object 同时打印到屏幕和写入文件
& $ADB shell "export CLASSPATH=$RemoteApk; app_process / $TargetClass" 2>&1 | Tee-Object -FilePath $ConsoleOutFile

# 程序执行完毕后，抓取 Logcat
Write-Host "----------------------------------------"
Write-Host ">> [ Dumping Logcat to file ]" -ForegroundColor Yellow

# 💡 [关键改动]: dump 日志并直接写入文件。去掉 -v color 避免文本中出现 ANSI 乱码，使用 -v threadtime 提供更详细的进程/线程信息
& $ADB logcat -d -v threadtime | Out-File -FilePath $LogcatFile -Encoding UTF8
& $ADB logcat -d -v threadtime ZR.Main:V ZR.Display:V ZR.GL:V ZeroRecorder:V *:S | Out-File -FilePath $FilteredLogcatFile -Encoding UTF8

Write-Host ">> DONE! 日志已全部保存至 $LogDir" -ForegroundColor Green
Write-Host "`n>> Execution finished. Press ENTER to exit."
Read-Host
