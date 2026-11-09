<#
.SYNOPSIS
    TG云盘：下载 TDLib 预编译库（up9cloud/android-libtdjson v1.8.65）
    并只保留 arm64-v8a/libtdjson.so 放入 app/src/main/jniLibs/。

.DESCRIPTION
    流程：
      1. 幂等检查：目标 so 已存在则跳过（-Force 强制重新拉取）
      2. 下载 https://github.com/up9cloud/android-libtdjson/releases/download/v1.8.65/libs.tar.gz
         （4 ABI 纯 libtdjson.so 集合，约 40.9 MB，2026-06-30 发版，MIT）
      3. SHA256 强校验：cb92b2dd62489f016c92e26f604c1658d87b0c3bbb56ca56ced6870def05f401
         （release 页面附带值；若上游更新可用 -ExpectedSha256 覆盖，传空字符串则只报告实际值）
      4. 解压后仅提取 arm64-v8a/libtdjson.so，其余 ABI 不落盘
      5. 打印产物路径与大小

    兼容性：Windows PowerShell 5.1 与 PowerShell 7+ 均可运行（无 pwsh 独有语法）。
    解压优先用系统 tar.exe（Win10 1803+ 自带）；缺失时回退内置纯 PowerShell tar 解析。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\fetch-tdlib.ps1
    powershell -ExecutionPolicy Bypass -File scripts\fetch-tdlib.ps1 -Force
#>
[CmdletBinding()]
param(
    [switch]$Force,
    [string]$Url = 'https://github.com/up9cloud/android-libtdjson/releases/download/v1.8.65/libs.tar.gz',
    [string]$ExpectedSha256 = 'cb92b2dd62489f016c92e26f604c1658d87b0c3bbb56ca56ced6870def05f401'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # PS5.1 的 IWR 进度条渲染极慢，关掉提速
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
} catch { }

$ScriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
$RepoRoot  = (Resolve-Path (Join-Path $ScriptDir '..')).Path
$DestDir   = Join-Path $RepoRoot 'app\src\main\jniLibs\arm64-v8a'
$DestSo    = Join-Path $DestDir 'libtdjson.so'
$TmpTar    = Join-Path ([System.IO.Path]::GetTempPath()) 'tgdrive-tdlib-libs.tar.gz'
$Stage     = Join-Path $RepoRoot '.tdlib-stage'

function Info([string]$m) { Write-Host "[fetch-tdlib] $m" }
function Ok([string]$m)   { Write-Host "[fetch-tdlib] $m" -ForegroundColor Green }
function Warn([string]$m) { Write-Host "[fetch-tdlib] $m" -ForegroundColor Yellow }
function Die([string]$m)  { Write-Host "[fetch-tdlib] $m" -ForegroundColor Red; exit 1 }

# ---------------------------------------------------------------------------
# 内置纯 PowerShell tar.gz 解析（tar.exe 缺失时的回退，兼容 PS 5.1）
# ---------------------------------------------------------------------------
function Read-TarBlock {
    param($Stream, [byte[]]$Buffer, [int]$Count)
    $total = 0
    while ($total -lt $Count) {
        $n = $Stream.Read($Buffer, $total, $Count - $total)
        if ($n -le 0) { break }
        $total += $n
    }
    return $total
}

function Read-TarBytes {
    param($Stream, [int]$Count)
    $buf = New-Object byte[] $Count
    $total = 0
    while ($total -lt $Count) {
        $n = $Stream.Read($buf, $total, $Count - $total)
        if ($n -le 0) { break }
        $total += $n
    }
    if ($total -lt $Count) { throw "tar 数据流提前结束" }
    return $buf
}

function Expand-TarGz {
    param([string]$TarGzPath, [string]$OutputDir)
    Add-Type -AssemblyName System.IO.Compression | Out-Null
    $fs  = [System.IO.File]::OpenRead($TarGzPath)
    $gz  = New-Object System.IO.Compression.GZipStream($fs, [System.IO.Compression.CompressionMode]::Decompress)
    $hdr = New-Object byte[] 512
    $pendingLongName = ''
    try {
        while ($true) {
            if ((Read-TarBlock $gz $hdr 512) -lt 512) { break }   # EOF
            $allZero = $true
            foreach ($b in $hdr) { if ($b -ne 0) { $allZero = $false; break } }
            if ($allZero) { continue }                            # 结束零块，读完剩余即可

            $sizeStr = [System.Text.Encoding]::ASCII.GetString($hdr, 124, 12).Trim([char]0, ' ')
            if ($sizeStr -eq '') { $size = 0 } else { $size = [Convert]::ToInt64(($sizeStr -replace '[^0-7]', ''), 8) }
            if ($size -lt 0) { $size = 0 }
            $tf        = [int]$hdr[156]
            $name      = [System.Text.Encoding]::UTF8.GetString($hdr, 0, 100).Trim([char]0, ' ')
            $magic     = [System.Text.Encoding]::ASCII.GetString($hdr, 257, 6)
            if ($magic -like 'ustar*') {
                $prefix = [System.Text.Encoding]::UTF8.GetString($hdr, 345, 155).Trim([char]0, ' ')
                if ($prefix) { $name = "$prefix/$name" }
            }
            $pad = [int]([Math]::Ceiling($size / 512.0) * 512)
            $isRegular = ($tf -eq 48 -or $tf -eq 0)               # '0' 或旧式 NUL

            if ($tf -eq 76) {                                      # GNU 'L' 长文件名
                $pendingLongName = [System.Text.Encoding]::UTF8.GetString((Read-TarBytes $gz $size)).Trim([char]0)
                if ($pad -gt $size) { Read-TarBytes $gz ($pad - $size) | Out-Null }
                continue
            }
            if (-not $isRegular) {                                 # pax 头/目录/链接等：跳过 payload
                if ($pad -gt 0) { Read-TarBytes $gz $pad | Out-Null }
                continue
            }
            if (-not $name -and $pendingLongName) { $name = $pendingLongName }
            $pendingLongName = ''

            $outPath = Join-Path $OutputDir ($name -replace '/', [System.IO.Path]::DirectorySeparatorChar)
            $dir = Split-Path -Parent $outPath
            if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
            $ofs = [System.IO.File]::Create($outPath)
            try {
                $remain = $size
                while ($remain -gt 0) {
                    $n = [Math]::Min(65536, $remain)
                    $chunk = Read-TarBytes $gz ([int]$n)
                    $ofs.Write($chunk, 0, $chunk.Length)
                    $remain -= $chunk.Length
                }
            } finally { $ofs.Dispose() }
            if ($pad -gt $size) { Read-TarBytes $gz ($pad - $size) | Out-Null }
        }
    } finally {
        $gz.Dispose()
        $fs.Dispose()
    }
}

# ---------------------------------------------------------------------------
# 1. 幂等检查
# ---------------------------------------------------------------------------
if ((Test-Path -LiteralPath $DestSo) -and -not $Force) {
    $existSize = (Get-Item -LiteralPath $DestSo).Length
    Ok ("已存在 {0}（{1:N2} MB），跳过。重新拉取请加 -Force" -f $DestSo, ($existSize / 1MB))
    exit 0
}

# ---------------------------------------------------------------------------
# 2. 下载（IWR 优先，WebClient 兜底）
# ---------------------------------------------------------------------------
Info "下载 $Url"
$sw = [System.Diagnostics.Stopwatch]::StartNew()
try {
    $iwrArgs = @{ Uri = $Url; OutFile = $TmpTar }
    if ($PSVersionTable.PSVersion.Major -lt 6) { $iwrArgs['UseBasicParsing'] = $true }
    Invoke-WebRequest @iwrArgs
} catch {
    Warn "Invoke-WebRequest 失败（$($_.Exception.Message)），改用 WebClient 重试..."
    $wc = New-Object System.Net.WebClient
    try { $wc.DownloadFile($Url, $TmpTar) } finally { $wc.Dispose() }
}
$sw.Stop()
if (-not (Test-Path -LiteralPath $TmpTar)) { Die "下载失败：$TmpTar 不存在" }
$tarSize = (Get-Item -LiteralPath $TmpTar).Length
Info ("下载完成：{0:N2} MB，用时 {1:N1}s" -f ($tarSize / 1MB), $sw.Elapsed.TotalSeconds)

# ---------------------------------------------------------------------------
# 3. SHA256 校验
# ---------------------------------------------------------------------------
$actual = (Get-FileHash -Path $TmpTar -Algorithm SHA256).Hash.ToLower()
if (-not [string]::IsNullOrEmpty($ExpectedSha256)) {
    if ($actual -ne $ExpectedSha256.ToLower()) {
        Die ("SHA256 不匹配！`n  期望: {0}`n  实际: {1}`n  请到 release 页核对后用 -ExpectedSha256 传入新值" -f $ExpectedSha256.ToLower(), $actual)
    }
    Ok ("SHA256 校验通过：{0}" -f $actual)
} else {
    Warn ("未提供期望值，实际 SHA256 = {0}（请自行与 release 页核对）" -f $actual)
}

# ---------------------------------------------------------------------------
# 4. 解压（tar.exe 优先，纯 PS 回退）
# ---------------------------------------------------------------------------
if (Test-Path -LiteralPath $Stage) { Remove-Item -LiteralPath $Stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $Stage | Out-Null

$tarCmd = Get-Command tar.exe -ErrorAction SilentlyContinue
if ($tarCmd) {
    Info "使用系统 tar.exe 解压..."
    & $tarCmd.Path -xzf $TmpTar -C $Stage
    if ($LASTEXITCODE -ne 0) { Die "tar.exe 解压失败（exit $LASTEXITCODE）" }
} else {
    Warn "系统无 tar.exe（Win10 1803 以前），使用内置纯 PowerShell tar 解析（较慢）..."
    Expand-TarGz -TarGzPath $TmpTar -OutputDir $Stage
}

# ---------------------------------------------------------------------------
# 5. 只保留 arm64-v8a/libtdjson.so
# ---------------------------------------------------------------------------
$so = Get-ChildItem -Path $Stage -Recurse -Filter 'libtdjson.so' -File |
      Where-Object { $_.FullName -like '*arm64-v8a*' } |
      Select-Object -First 1
if (-not $so) {
    $abis = Get-ChildItem -Path $Stage -Recurse -Directory | ForEach-Object { $_.Name } | Select-Object -Unique
    Die ("未在压缩包中找到 arm64-v8a/libtdjson.so。包内目录：{0}" -f (($abis | Select-Object -First 12) -join ', '))
}
New-Item -ItemType Directory -Force -Path $DestDir | Out-Null
Copy-Item -LiteralPath $so.FullName -Destination $DestSo -Force

# ---------------------------------------------------------------------------
# 6. 产物报告 + 清理
# ---------------------------------------------------------------------------
$soSize = (Get-Item -LiteralPath $DestSo).Length
Ok ("产物路径：{0}" -f $DestSo)
Ok ("产物大小：{0:N2} MB（源包 {1:N2} MB）" -f ($soSize / 1MB), ($tarSize / 1MB))
Remove-Item -LiteralPath $Stage -Recurse -Force -ErrorAction SilentlyContinue
Info "临时解压目录已清理；下载缓存保留在 $TmpTar（可手动删除）"
Ok "完成。现在可用 Android Studio 打开 $RepoRoot 构建。"
exit 0
