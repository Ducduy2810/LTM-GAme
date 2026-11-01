param([switch]$ExportPdf)
$ErrorActionPreference='Stop'
$root=$PSScriptRoot
$word=$null
$document=$null
$saveNo=0
$confirm=$false
$readOnly=$true
try {
    $word=New-Object -ComObject Word.Application
    $word.Visible=$false
    $word.DisplayAlerts=0
    $word.AutomationSecurity=3
    if(-not $ExportPdf) {
        $originalPath=Join-Path $root 'Khung_bao_cao_Vu_Mon_Tranh_Hung.original.docx'
        $original=$word.Documents.Open([ref]$originalPath,[ref]$confirm,[ref]$readOnly)
        $original.Repaginate()
        $originalPages=$original.ComputeStatistics(2)
        $original.Close([ref]$saveNo)
        [void][Runtime.InteropServices.Marshal]::ReleaseComObject($original)
    }
    $revisedPath=Join-Path $root 'Bao_cao_Vu_Mon_Tranh_Hung_rut_gon.docx'
    $document=$word.Documents.Open([ref]$revisedPath,[ref]$confirm,[ref]$readOnly)
    $document.Repaginate()
    $pages=$document.ComputeStatistics(2)
    if($ExportPdf) {
        $pdfPath=Join-Path $root 'Bao_cao_Vu_Mon_Tranh_Hung_rut_gon.pdf'
        $document.ExportAsFixedFormat($pdfPath,17)
        [pscustomobject]@{Pages=$pages;Pdf=$pdfPath} | ConvertTo-Json
    } else {
        $targets=Get-Content (Join-Path $root 'index-targets.json') -Encoding UTF8 -Raw | ConvertFrom-Json
        $document.Bookmarks.ShowHidden=$true
        $pageMap=@{}
        foreach($row in $targets) {
            if(-not $document.Bookmarks.Exists($row.bookmark)) {throw "Missing bookmark: $($row.bookmark)"}
            $bookmark=$document.Bookmarks.Item($row.bookmark)
            $pageMap[$row.bookmark]=[string]$bookmark.Range.Information(1)
        }
        [pscustomobject]@{OriginalPages=$originalPages;RevisedPages=$pages;PageMap=$pageMap} | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $root 'pagination.json') -Encoding UTF8
        [pscustomobject]@{OriginalPages=$originalPages;RevisedPages=$pages;IndexEntries=$targets.Count} | ConvertTo-Json
    }
} catch {
    Write-Output ('WORD ERROR: '+$_.Exception.Message+' at '+$_.ScriptStackTrace)
    throw
} finally {
    if($null -ne $document) {$document.Close([ref]$saveNo);[void][Runtime.InteropServices.Marshal]::ReleaseComObject($document)}
    if($null -ne $word) {$word.Quit([ref]$saveNo);[void][Runtime.InteropServices.Marshal]::ReleaseComObject($word)}
    [GC]::Collect()
    [GC]::WaitForPendingFinalizers()
}
