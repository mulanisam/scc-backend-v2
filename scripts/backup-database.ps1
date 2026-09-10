<#
.SYNOPSIS
    Backs up the poultry database and proves the backup is real.

.DESCRIPTION
    Replaces mysqlbackup.bat, which produced nothing for at least three weeks
    without anyone noticing. What was wrong with it:

      set mysql_user=me                        - no such user; the working one is root
      set mysql_password=Akash@13              - in plain text, and wrong
      set backup_path=F:\New Folder\MySQL Backup   - that directory does not exist
      ...--result-file="%backup_path%\my-all-databases.sql"

    mysqldump therefore failed on every run, and the failure branch wrote its
    message to a log file in that same missing directory - so the error was lost
    as well. The three .sql.gz files in Downloads, dated 26 Aug to 9 Sep, are all
    20 bytes and decompress to zero. The filename was also fixed, so even a
    working version would have kept exactly one backup and overwritten it daily.

    This script fixes the causes rather than the symptoms:

      - credentials come from .env, never from the script
      - the destination is created if missing, instead of failing
      - every run writes its own timestamped file, so history accumulates
      - the dump is verified before it is called a success
      - a failure is loud: non-zero exit code, a written log line, and an error
        on the console, so Task Scheduler shows "last result 0x1"
      - old backups are pruned on a stated retention, not left to grow

    The verification is the point. A backup nobody has restored is a guess, so
    this checks three things mysqldump getting it wrong would break: the file
    exists, it is larger than a plausible floor, and it ends with mysqldump's own
    "-- Dump completed on" marker, which is written last and only on success.

.PARAMETER Database
    Which database to dump. Defaults to the one named in .env.

.PARAMETER OutputDirectory
    Where the backups go. Created if it does not exist.

.PARAMETER RetentionDays
    Backups older than this are deleted after a successful run. 0 keeps everything.

.PARAMETER Compress
    Also produce a .zip and delete the plain .sql. Uses Compress-Archive, which
    is built into Windows PowerShell - the old workflow's .gz files needed
    something that was not installed.

.EXAMPLE
    .\scripts\backup-database.ps1

.EXAMPLE
    .\scripts\backup-database.ps1 -Compress -RetentionDays 30

.NOTES
    To run it nightly at 1am, from an elevated PowerShell:

      $action  = New-ScheduledTaskAction -Execute "powershell.exe" `
                   -Argument '-NoProfile -ExecutionPolicy Bypass -File "C:\Users\mulan\Downloads\scc-backend-upgraded\scc-backend-upgraded\scripts\backup-database.ps1" -Compress'
      $trigger = New-ScheduledTaskTrigger -Daily -At 1am
      Register-ScheduledTask -TaskName "Poultry DB backup" -Action $action -Trigger $trigger -RunLevel Highest

    Then check Task Scheduler's "Last Run Result" occasionally. It reads 0x0 only
    when the dump was verified.
#>

[CmdletBinding()]
param(
    [string] $Database,
    [string] $OutputDirectory,
    [int]    $RetentionDays = 30,
    [switch] $Compress
)

$ErrorActionPreference = 'Stop'

# --- paths ------------------------------------------------------------------
# Resolved from this script's own location, so the task scheduler's working
# directory does not matter.
$projectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$envFile     = Join-Path $projectRoot '.env'

if (-not $OutputDirectory) { $OutputDirectory = Join-Path $projectRoot 'backups' }
$logFile = Join-Path $OutputDirectory 'backup-log.txt'

function Write-Log {
    param([string] $Message, [string] $Level = 'INFO')
    $line = "{0} [{1}] {2}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Level, $Message
    Write-Host $line
    try { Add-Content -Path $logFile -Value $line -Encoding utf8 } catch { }
}

$script:cleanupPaths = @()

function Fail {
    param([string] $Message)
    # A half-written dump is worse than none: it looks like a backup in a
    # directory listing. The old workflow left three such files lying around.
    foreach ($path in $script:cleanupPaths) {
        if ($path -and (Test-Path $path)) {
            Remove-Item $path -Force -ErrorAction SilentlyContinue
            Write-Log "Removed the incomplete file $(Split-Path $path -Leaf)" 'WARN'
        }
    }
    Write-Log $Message 'ERROR'
    Write-Error $Message
    exit 1
}

# --- destination first, so a failure has somewhere to be recorded -----------
if (-not (Test-Path $OutputDirectory)) {
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    Write-Log "Created backup directory $OutputDirectory"
}

# --- credentials from .env --------------------------------------------------
if (-not (Test-Path $envFile)) {
    Fail ".env not found at $envFile. Copy .env.example to .env and fill it in."
}

$settings = @{}
foreach ($line in Get-Content $envFile) {
    $trimmed = $line.Trim()
    if ($trimmed -eq '' -or $trimmed.StartsWith('#')) { continue }
    $split = $trimmed.IndexOf('=')
    if ($split -lt 1) { continue }
    $settings[$trimmed.Substring(0, $split).Trim()] = $trimmed.Substring($split + 1).Trim()
}

$dbUser     = $settings['DATASOURCE_USER']
$dbPassword = $settings['DATASOURCE_PASSWORD']
$dbUrl      = $settings['DATASOURCE_URL']

if (-not $dbUser)     { Fail "DATASOURCE_USER is not set in $envFile" }
if (-not $dbPassword) { Fail "DATASOURCE_PASSWORD is not set in $envFile" }

# jdbc:mysql://host:port/name?params -> host, port, name
if (-not $Database -or -not $dbUrl) {
    if ($dbUrl -match 'jdbc:mysql://([^:/]+)(?::(\d+))?/([A-Za-z0-9_]+)') {
        $dbHost = $Matches[1]
        $dbPort = if ($Matches[2]) { $Matches[2] } else { '3306' }
        if (-not $Database) { $Database = $Matches[3] }
    } else {
        Fail "Could not read the host and database from DATASOURCE_URL in $envFile"
    }
} else {
    $dbHost = 'localhost'
    $dbPort = '3306'
}

# --- mysqldump --------------------------------------------------------------
$mysqldump = Get-Command mysqldump.exe -ErrorAction SilentlyContinue
if ($mysqldump) {
    $mysqldumpPath = $mysqldump.Source
} else {
    # Not on PATH: look where the MySQL installer puts it.
    $candidates = Get-ChildItem 'C:\Program Files\MySQL' -Filter 'mysqldump.exe' -Recurse -ErrorAction SilentlyContinue
    if (-not $candidates) { Fail "mysqldump.exe not found. Add the MySQL bin folder to PATH." }
    $mysqldumpPath = $candidates[0].FullName
}

$stamp      = Get-Date -Format 'yyyy-MM-dd_HH-mm-ss'
$sqlFile    = Join-Path $OutputDirectory "$Database`_$stamp.sql"
$errorFile  = Join-Path $env:TEMP "mysqldump-$stamp.err"

Write-Log "Backing up $Database from $dbHost`:$dbPort as $dbUser to $sqlFile"

$script:cleanupPaths += $sqlFile

# The credentials go in a temporary option file rather than on the command line.
# Two reasons: an argument is visible to anyone who can list processes, and
# mysqldump prints "[Warning] Using a password on the command line interface can
# be insecure" to stderr, which then muddies the genuine error message on a
# failure. The file is written under the current user's TEMP and deleted below,
# including when the dump fails.
$optionFile = Join-Path $env:TEMP "mysqldump-$stamp.cnf"
$script:cleanupPaths += $optionFile

@(
    '[mysqldump]'
    "user=$dbUser"
    "password=$dbPassword"
    "host=$dbHost"
    "port=$dbPort"
) | Set-Content -Path $optionFile -Encoding ascii

# --single-transaction takes a consistent InnoDB snapshot without locking the
# tables, which the old script omitted - so a dump taken during trading was not
# guaranteed to be coherent.
$arguments = @(
    "--defaults-extra-file=$optionFile"
    '--single-transaction'
    '--routines'
    '--triggers'
    '--events'
    '--default-character-set=utf8mb4'
    "--result-file=$sqlFile"
    $Database
)

$process = Start-Process -FilePath $mysqldumpPath -ArgumentList $arguments `
    -NoNewWindow -Wait -PassThru -RedirectStandardError $errorFile

# Guarded rather than chained: Get-Content -Raw on an empty file returns $null,
# and calling .Trim() on it throws. On a successful run stderr genuinely is
# empty now that the credentials no longer come from the command line, so this
# path is the normal one.
$stderrRaw = if (Test-Path $errorFile) { Get-Content $errorFile -Raw } else { $null }
$stderr = if ($stderrRaw) { $stderrRaw.Trim() } else { '' }
Remove-Item $errorFile -ErrorAction SilentlyContinue
Remove-Item $optionFile -Force -ErrorAction SilentlyContinue
$script:cleanupPaths = $script:cleanupPaths | Where-Object { $_ -ne $optionFile }

if ($process.ExitCode -ne 0) {
    Fail "mysqldump exited with $($process.ExitCode). $stderr"
}

# --- verification -----------------------------------------------------------
# This is what the old script never did. Each check corresponds to a way the
# dump can be wrong while the command still appears to have run.

if (-not (Test-Path $sqlFile)) {
    Fail "mysqldump reported success but $sqlFile does not exist. $stderr"
}

$size = (Get-Item $sqlFile).Length
$floorBytes = 100KB
if ($size -lt $floorBytes) {
    Fail ("Backup is only {0} bytes, below the {1} byte floor - treating as failed. This is what the old backups looked like: 20 bytes, empty. {2}" -f $size, $floorBytes, $stderr)
}

# mysqldump writes this line last, and only when it finished cleanly. A dump cut
# short by a lost connection or a full disk will not have it.
$tail = Get-Content $sqlFile -Tail 5 -ErrorAction SilentlyContinue
if (-not ($tail -match 'Dump completed on')) {
    Fail "Backup is missing mysqldump's completion marker, so it is truncated. $sqlFile"
}

# A dump of the right database with actual rows in it.
$head = Get-Content $sqlFile -TotalCount 40 -ErrorAction SilentlyContinue
if (-not ($head -match [regex]::Escape($Database))) {
    Write-Log "Warning: the dump header does not mention $Database - check the file." 'WARN'
}
if (-not (Select-String -Path $sqlFile -Pattern 'INSERT INTO' -SimpleMatch -Quiet)) {
    Fail "Backup contains no INSERT statements, so it holds no data. $sqlFile"
}

Write-Log ("Verified: {0} ({1:N1} MB), completion marker present, contains data" -f (Split-Path $sqlFile -Leaf), ($size / 1MB))

# Verified, so it is no longer a partial file to clean up - anything that fails
# from here on must leave this dump in place.
$script:cleanupPaths = @()

# --- compression ------------------------------------------------------------
$finalFile = $sqlFile
if ($Compress) {
    $zipFile = [IO.Path]::ChangeExtension($sqlFile, '.zip')
    Compress-Archive -Path $sqlFile -DestinationPath $zipFile -CompressionLevel Optimal -Force

    if (-not (Test-Path $zipFile) -or (Get-Item $zipFile).Length -lt 1KB) {
        Fail "Compression produced nothing usable at $zipFile - the .sql is kept at $sqlFile"
    }

    Remove-Item $sqlFile
    $finalFile = $zipFile
    Write-Log ("Compressed to {0} ({1:N1} MB)" -f (Split-Path $zipFile -Leaf), ((Get-Item $zipFile).Length / 1MB))
}

# --- retention --------------------------------------------------------------
# Only after a verified success, so a run of failures can never delete the last
# good backup.
if ($RetentionDays -gt 0) {
    $cutoff = (Get-Date).AddDays(-$RetentionDays)
    $stale = Get-ChildItem $OutputDirectory -File |
             Where-Object { $_.Name -like "$Database`_*" -and
                            ($_.Extension -in '.sql', '.zip') -and
                            $_.LastWriteTime -lt $cutoff }
    foreach ($old in $stale) {
        Remove-Item $old.FullName
        Write-Log "Pruned $($old.Name), older than $RetentionDays days"
    }
}

$kept = (Get-ChildItem $OutputDirectory -File |
         Where-Object { $_.Extension -in '.sql', '.zip' }).Count
Write-Log "Backup complete: $finalFile. $kept backup file(s) retained."
exit 0
