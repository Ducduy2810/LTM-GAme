$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$path=Join-Path $PSScriptRoot 'Bao_cao_Vu_Mon_Tranh_Hung_rut_gon.docx'
$pages=@{}
Get-Content (Join-Path $PSScriptRoot 'pagination.tsv') | ForEach-Object {
    $pair=$_ -split "`t",2
    $pages[$pair[0]]=$pair[1]
}
$zip=[IO.Compression.ZipFile]::Open($path,[IO.Compression.ZipArchiveMode]::Update)
try {
    $reader=[IO.StreamReader]::new($zip.GetEntry('word/document.xml').Open())
    $doc=[Xml.XmlDocument]::new()
    $doc.PreserveWhitespace=$true
    $doc.LoadXml($reader.ReadToEnd())
    $reader.Dispose()
    $w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'
    $ns=[Xml.XmlNamespaceManager]::new($doc.NameTable)
    $ns.AddNamespace('w',$w)
    $links=@($doc.SelectNodes('//w:hyperlink[starts-with(@w:anchor,"ShortReport_")]',$ns))
    foreach($link in $links) {
        $name=$link.GetAttribute('anchor',$w)
        if(-not $pages.ContainsKey($name)){throw "Page not found: $name"}
        $textNodes=@($link.SelectNodes('./w:r[w:t]/w:t',$ns))
        if($textNodes.Count -ne 2){throw "Unexpected index row: $name"}
        $textNodes[1].InnerText=$pages[$name]
        if($name -eq 'ShortReport_1431') {
            $textNodes[0].InnerText=$textNodes[0].InnerText.Replace('VI I ','VII ')
        }
    }
    $zip.GetEntry('word/document.xml').Delete()
    $entry=$zip.CreateEntry('word/document.xml',[IO.Compression.CompressionLevel]::Optimal)
    $settings=[Xml.XmlWriterSettings]::new()
    $settings.Encoding=[Text.UTF8Encoding]::new($false)
    $stream=$entry.Open()
    $writer=[Xml.XmlWriter]::Create($stream,$settings)
    try {$doc.Save($writer)} finally {$writer.Dispose();$stream.Dispose()}
    'Updated page numbers: '+$links.Count
} finally {$zip.Dispose()}
