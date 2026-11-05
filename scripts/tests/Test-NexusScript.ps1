[CmdletBinding()]
param()

# Tests du module scripts/lib/NexusScript.psm1 sur des wrappers Maven factices :
# aucun Maven reel n'est telecharge ni execute.

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$modulePath = Join-Path $PSScriptRoot '..\lib\NexusScript.psm1'
Import-Module $modulePath -Force

$isWindowsHost = $env:OS -eq 'Windows_NT'
$workRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("nexus-script-tests-" + [guid]::NewGuid().ToString('N'))
$failures = New-Object System.Collections.Generic.List[string]
$savedCi = $env:CI
$savedGithubActions = $env:GITHUB_ACTIONS
$savedPath = $env:PATH

function Write-FakeMaven {
    param(
        [Parameter(Mandatory = $true)][string]$Directory,
        [Parameter(Mandatory = $true)][string]$BaseName,
        [Parameter(Mandatory = $true)][int]$VersionExitCode,
        [Parameter(Mandatory = $true)][int]$RunExitCode
    )

    New-Item -ItemType Directory -Force -Path $Directory | Out-Null
    if ($isWindowsHost) {
        $lines = @(
            '@echo off',
            'if "%~1"=="--version" (',
            '  echo Apache Maven factice',
            "  exit /b $VersionExitCode",
            ')',
            "exit /b $RunExitCode"
        )
        $file = Join-Path $Directory "$BaseName.cmd"
        [System.IO.File]::WriteAllText($file, (($lines -join "`r`n") + "`r`n"), [System.Text.Encoding]::ASCII)
    }
    else {
        $lines = @(
            '#!/bin/sh',
            'if [ "$1" = "--version" ]; then',
            '  echo "Apache Maven factice"',
            "  exit $VersionExitCode",
            'fi',
            "exit $RunExitCode"
        )
        $file = Join-Path $Directory $BaseName
        [System.IO.File]::WriteAllText($file, (($lines -join "`n") + "`n"), [System.Text.Encoding]::ASCII)
        & chmod +x $file
    }
}

function Set-ContinuousIntegration([bool]$Enabled) {
    if ($Enabled) {
        $env:CI = 'true'
    }
    else {
        Remove-Item Env:CI -ErrorAction SilentlyContinue
        Remove-Item Env:GITHUB_ACTIONS -ErrorAction SilentlyContinue
    }
}

function Invoke-TestCase {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][scriptblock]$Body
    )

    try {
        & $Body
        Write-Host "PASS $Name"
    }
    catch {
        $failures.Add("$Name : $($_.Exception.Message)")
        Write-Host "FAIL $Name : $($_.Exception.Message)"
    }
}

try {
    Invoke-TestCase 'wrapper present : Maven execute par le wrapper' {
        $root = Join-Path $workRoot 'wrapper-ok'
        Write-FakeMaven -Directory $root -BaseName 'mvnw' -VersionExitCode 0 -RunExitCode 0
        Set-ContinuousIntegration $true
        Invoke-Maven -RepositoryRoot $root -Arguments @('-q', 'package')
    }

    Invoke-TestCase 'wrapper en echec avec CI definie : exception sans repli' {
        $root = Join-Path $workRoot 'wrapper-broken'
        Write-FakeMaven -Directory $root -BaseName 'mvnw' -VersionExitCode 7 -RunExitCode 0
        $fallbackBin = Join-Path $workRoot 'fallback-bin-ci'
        Write-FakeMaven -Directory $fallbackBin -BaseName 'mvn' -VersionExitCode 0 -RunExitCode 0
        $env:PATH = $fallbackBin + [System.IO.Path]::PathSeparator + $savedPath
        Set-ContinuousIntegration $true
        $thrown = $null
        try {
            Invoke-Maven -RepositoryRoot $root -Arguments @('package')
        }
        catch {
            $thrown = $_.Exception.Message
        }
        finally {
            $env:PATH = $savedPath
        }
        if ($null -eq $thrown) {
            throw 'aucune exception levee alors que le wrapper est en echec en CI'
        }
        if ($thrown -notmatch 'exige le Maven Wrapper verifie') {
            throw "message inattendu : $thrown"
        }
    }

    Invoke-TestCase 'code retour Maven non nul propage' {
        $root = Join-Path $workRoot 'maven-fails'
        Write-FakeMaven -Directory $root -BaseName 'mvnw' -VersionExitCode 0 -RunExitCode 3
        Set-ContinuousIntegration $true
        $exception = $null
        try {
            Invoke-Maven -RepositoryRoot $root -Arguments @('verify')
        }
        catch {
            $exception = $_.Exception
        }
        if ($null -eq $exception) {
            throw 'aucune exception levee pour un code Maven non nul'
        }
        if ($exception.Data['ExitCode'] -ne 3) {
            throw "code propage inattendu : $($exception.Data['ExitCode'])"
        }
        if ($exception.Message -notmatch 'code 3') {
            throw "message inattendu : $($exception.Message)"
        }
    }

    Invoke-TestCase 'wrapper absent hors CI : repli averti sur le Maven installe' {
        $root = Join-Path $workRoot 'wrapper-missing'
        New-Item -ItemType Directory -Force -Path $root | Out-Null
        $fallbackBin = Join-Path $workRoot 'fallback-bin-local'
        Write-FakeMaven -Directory $fallbackBin -BaseName 'mvn' -VersionExitCode 0 -RunExitCode 0
        $env:PATH = $fallbackBin + [System.IO.Path]::PathSeparator + $savedPath
        Set-ContinuousIntegration $false
        try {
            $warnings = $null
            Invoke-Maven -RepositoryRoot $root -Arguments @('package') -WarningVariable warnings -WarningAction SilentlyContinue
        }
        finally {
            $env:PATH = $savedPath
        }
        if (@($warnings | Where-Object { "$_" -match 'Repli local' }).Count -ne 1) {
            throw 'le repli local doit emettre un avertissement'
        }
    }

    Invoke-TestCase 'Resolve-AbsolutePath : chemin relatif resolu contre la base' {
        $base = [System.IO.Path]::GetFullPath($workRoot)
        $resolved = Resolve-AbsolutePath -Path 'a/b.json' -BasePath $base
        $expected = [System.IO.Path]::GetFullPath((Join-Path $base 'a/b.json'))
        if ($resolved -ne $expected) {
            throw "attendu $expected, obtenu $resolved"
        }
    }
}
finally {
    $env:CI = $savedCi
    $env:GITHUB_ACTIONS = $savedGithubActions
    $env:PATH = $savedPath
    Remove-Item -Recurse -Force -LiteralPath $workRoot -ErrorAction SilentlyContinue
}

if ($failures.Count -gt 0) {
    Write-Host "$($failures.Count) test(s) NexusScript en echec."
    exit 1
}
Write-Host 'Tests NexusScript : tous PASS.'
exit 0
