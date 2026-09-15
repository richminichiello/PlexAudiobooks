# Create the upload folder
#New-Item -ItemType Directory -Path "c:\temp\claude_upload" -Force


$ktFiles = Get-ChildItem -Recurse -Filter "*.kt" C:\Temp\PlexAudiobooks\app\src\main\java
$xmlFiles = Get-ChildItem -Recurse -Filter "*.xml" C:\Temp\PlexAudiobooks\app\src\main\res\layout
$mdFiles = Get-Childitem -Recurse -Filter "*.md" C:\Temp\PlexAudiobooks


copy-item C:\Temp\PlexAudiobooks\build.gradle C:\Temp\claude_upload\build.gradle.txt -Force


foreach ($ktfile in $ktfiles) {
    write-host Filename is $ktfile.name
    Write-host FullName is $ktfile.fullname
    Copy-Item $ktfile.FullName "c:\temp\claude_upload\$($ktfile.Name).txt" -Force
}

foreach ($xmlfile in $xmlfiles) {
    write-host Filename is $xmlfile.name
    Write-host FullName is $xmlfile.fullname
    Copy-Item $xmlfile.FullName "c:\temp\claude_upload\$($xmlfile.Name).txt" -Force
}

foreach ($mdfile in $mdfiles) {
    write-host Filename is $mdfile.name
    Write-host FullName is $mdfile.fullname
    Copy-Item $mdfile.FullName "c:\temp\claude_upload\$($mdfile.Name).txt" -Force
}