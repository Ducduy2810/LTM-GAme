$ErrorActionPreference = 'Stop'
$pdfPath = 'C:\Users\admin\Documents\ki1nam4\LTM-thayQuyen\3. Thể thức trình bày KLTN (QĐ-612 ĐHHN) (1) (1).pdf'
$textPath = Join-Path (Get-Location) 'scratch/thesis-format.txt'
$word = $null
$document = $null
try {
    $word = New-Object -ComObject Word.Application
    $word.Visible = $false
    $word.DisplayAlerts = 0
    $document = $word.Documents.Open($pdfPath, $false, $true, $false)
    [System.IO.File]::WriteAllText($textPath, $document.Content.Text, [System.Text.UTF8Encoding]::new($false))
    'PDF pages: ' + $document.ComputeStatistics(2)
    'Extracted: ' + $textPath
} finally {
    if ($null -ne $document) { $document.Close(0); [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($document) }
    if ($null -ne $word) { $word.Quit(); [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($word) }
}
