param(
    [switch]$Interactive,
    [switch]$Trace,
    [string]$Task,
    [string]$TaskFile,
    [string]$Workspace,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$MavenPath,
    [string[]]$AdditionalMavenArguments = @()
)

$ErrorActionPreference = 'Stop'
if ($Interactive -and ($PSBoundParameters.ContainsKey('Task') -or $PSBoundParameters.ContainsKey('TaskFile'))) {
    throw 'Use -Interactive without -Task or -TaskFile.'
}
if ($PSBoundParameters.ContainsKey('Task') -and $PSBoundParameters.ContainsKey('TaskFile')) {
    throw 'Use either -Task for analysis or -TaskFile for a development workflow, not both.'
}
if ($PSBoundParameters.ContainsKey('Task') -and [string]::IsNullOrWhiteSpace($Task)) {
    throw '-Task must contain an analysis request.'
}
if ($PSBoundParameters.ContainsKey('TaskFile')) {
    if ([string]::IsNullOrWhiteSpace($TaskFile)) { throw '-TaskFile must identify a task JSON file.' }
    if (-not [System.IO.Path]::IsPathRooted($TaskFile)) { $TaskFile = Join-Path $PSScriptRoot $TaskFile }
    $TaskFile = (Resolve-Path -LiteralPath $TaskFile).ProviderPath
    if (-not (Test-Path -LiteralPath $TaskFile -PathType Leaf)) { throw '-TaskFile must be a file.' }
}
$utf8Encoding = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8Encoding
[Console]::OutputEncoding = $utf8Encoding
$OutputEncoding = $utf8Encoding

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $JavaHome = Join-Path $env:USERPROFILE '.jdks/corretto-21.0.1'
}
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
    throw 'Java 21 was not found. Set JAVA_HOME or pass -JavaHome.'
}
$env:JAVA_HOME = $JavaHome

if ([string]::IsNullOrWhiteSpace($MavenPath)) {
    $mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($null -ne $mavenCommand) {
        $MavenPath = $mavenCommand.Source
    } else {
        $MavenPath = Join-Path $env:USERPROFILE 'maven-mvnd-1.0.2-windows-amd64/mvn/bin/mvn.cmd'
    }
}
if (-not (Test-Path -LiteralPath $MavenPath)) {
    throw 'Maven was not found. Add mvn.cmd to PATH or pass -MavenPath.'
}

Push-Location $PSScriptRoot
try {
    $mavenGoals = @('compile', 'exec:java')
    if ($Trace) { $mavenGoals += '-Dagent.trace=true' }
    # Only ASCII base64 crosses mvn.cmd; task/path text may contain shell metacharacters.
    if ($PSBoundParameters.ContainsKey('Task')) {
        $encodedTask = [Convert]::ToBase64String($utf8Encoding.GetBytes($Task))
        $mavenGoals += "-Dagent.taskEncoded=$encodedTask"
    }
    if ($Interactive) {
        $directive = Read-Host '[Analyst] What should we develop?'
        if ([string]::IsNullOrWhiteSpace($directive) -or $directive.Length -gt 3000) { throw 'Enter a directive of 1..3000 characters.' }
        $encodedDirective = [Convert]::ToBase64String($utf8Encoding.GetBytes($directive))
        $mavenGoals += '-Dagent.mode=interactive'
        $mavenGoals += "-Dagent.directiveEncoded=$encodedDirective"
    }
    if ($PSBoundParameters.ContainsKey('TaskFile')) {
        $mavenGoals += '-Dagent.mode=develop'
        $encodedTaskFile = [Convert]::ToBase64String($utf8Encoding.GetBytes($TaskFile))
        $mavenGoals += "-Dagent.taskFileEncoded=$encodedTaskFile"
    }
    if ($PSBoundParameters.ContainsKey('Workspace')) {
        if ([string]::IsNullOrWhiteSpace($Workspace)) { throw '-Workspace must identify a repository directory.' }
        $encodedWorkspace = [Convert]::ToBase64String($utf8Encoding.GetBytes($Workspace))
        $mavenGoals += "-Dagent.workspaceEncoded=$encodedWorkspace"
    }
    # Windows PowerShell wraps native stderr as ErrorRecord when output is redirected.
    # Preserve the process exit code instead of aborting the script at its first diagnostic.
    $savedErrorAction = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $MavenPath @mavenGoals @AdditionalMavenArguments 2>&1 | ForEach-Object { $_.ToString() }
        $runExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedErrorAction
    }
} finally {
    Pop-Location
}
exit $runExitCode
