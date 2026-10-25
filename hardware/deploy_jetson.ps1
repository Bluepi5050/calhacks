param(
    [Parameter(Mandatory = $true)][string]$JetsonIp,
    [string]$SshUser = "scott170",
    [string]$Model = "hardware/models/yolov8n.onnx"
)

$ErrorActionPreference = "Stop"
$remote = "$SshUser@$JetsonIp"
$project = "/home/$SshUser/calhacks"
$destination = "$project/hardware"
$codeFiles = @(
    "hardware/__init__.py",
    "hardware/coco.py",
    "hardware/crossing.py",
    "hardware/item_tracker.py",
    "hardware/events.py",
    "hardware/product_config.py",
    "hardware/scan_geometry.py",
    "hardware/scanner.py",
    "hardware/transport.py",
    "hardware/tensorrt_detector.py",
    "hardware/main_tensorrt.py"
)
foreach ($file in $codeFiles + @("hardware/models/coco_classes.json", $Model,
                                  "data/detectable_products.json", "data/scan_geometry.json")) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
        throw "Required deployment file is missing: $file"
    }
}
ssh $remote "mkdir -p '$destination/models' '$project/data'"
if ($LASTEXITCODE -ne 0) { throw "Could not create remote hardware directory" }
scp @codeFiles "$remote`:$destination/"
if ($LASTEXITCODE -ne 0) { throw "SCP code transfer failed" }
scp "hardware/models/coco_classes.json" $Model "$remote`:$destination/models/"
if ($LASTEXITCODE -ne 0) { throw "SCP file transfer failed" }
scp "data/detectable_products.json" "data/scan_geometry.json" "$remote`:$project/data/"
if ($LASTEXITCODE -ne 0) { throw "SCP configuration transfer failed" }
Write-Host "Transferred scanner files to $remote`:$destination"
