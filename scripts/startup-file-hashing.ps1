function Get-Sha256Hex([string]$Path) {
    $Stream = $null
    $Sha256 = $null
    try {
        $Stream = [System.IO.File]::OpenRead($Path)
        $Sha256 = [System.Security.Cryptography.SHA256]::Create()
        $HashBytes = $Sha256.ComputeHash($Stream)
        return [System.BitConverter]::ToString($HashBytes).Replace('-', '')
    }
    finally {
        if ($null -ne $Sha256) {
            $Sha256.Dispose()
        }
        if ($null -ne $Stream) {
            $Stream.Dispose()
        }
    }
}
