# Verify the native MSI installer contains a shortcut placed under the Start menu.
# The Windows Installer Shortcut table is the source of truth for installer shortcuts.
$ErrorActionPreference = "Stop"
$msiFiles = @(Get-ChildItem "app-desktop/build/compose/binaries/main/msi/*.msi" -File)
if ($msiFiles.Count -ne 1) {
    throw "Expected exactly one MSI installer; found $($msiFiles.Count)."
}

$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $installer.OpenDatabase($msiFiles[0].FullName, 0)
$view = $database.OpenView("SELECT Directory_, Name FROM Shortcut")
try {
    $view.Execute()
    $found = $false
    while ($record = $view.Fetch()) {
        $directory = $record.StringData(1)
        $name = $record.StringData(2)
        Write-Host "MSI shortcut: $directory / $name"
        if ($directory -match "ProgramMenu") {
            $found = $true
        }
    }
    if (-not $found) {
        throw "MSI does not contain a Windows Start menu shortcut."
    }
    Write-Host "MSI contains a Start menu shortcut."
}
finally {
    $view.Close()
}
