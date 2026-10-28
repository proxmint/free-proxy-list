$ErrorActionPreference = 'Stop'
$toolsDir = Split-Path -Parent $MyInvocation.MyCommand.Definition

# nodejs-lts is installed as a dependency earlier in this run; reload PATH so node.exe resolves.
Update-SessionEnvironment
$node = (Get-Command node.exe).Source

Install-BinFile -Name 'proxmint' -Path $node -Command "`"$toolsDir\proxmint\cli.js`""
