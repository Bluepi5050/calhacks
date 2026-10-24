param(
    [Parameter(Mandatory = $true)][string]$JetsonIp,
    [string]$SshUser = "scott170",
    [string]$Model = "hardware/models/yolov8n.onnx"
)

$ErrorActionPreference = "Stop"
$remote = "$SshUser@$JetsonIp"
$destination = "/home/$SshUser/calhacks/hardware"
$codeFiles = @(
    "hardware/__init__.py",
    "hardware/item_tracker.py",
    "hardware/events.py",
    "hardware/tensorrt_detector.py",
    "hardware/main_tensorrt.py"
)
foreach ($file in $codeFiles + @("hardware/models/coco_classes.json", $Model)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
        throw "Required deployment file is missing: $file"
    }
}
ssh $remote "mkdir -p '$destination/models'"
if ($LASTEXITCODE -ne 0) { throw "Could not create remote hardware directory" }
scp @codeFiles "$remote`:$destination/"
if ($LASTEXITCODE -ne 0) { throw "SCP code transfer failed" }
scp "hardware/models/coco_classes.json" $Model "$remote`:$destination/models/"
if ($LASTEXITCODE -ne 0) { throw "SCP file transfer failed" }
Write-Host "Transferred scanner files to $remote`:$destination"
