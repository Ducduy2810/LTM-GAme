$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$helperScript=[IO.File]::ReadAllText((Join-Path $PSScriptRoot '../edit_docx.ps1'),[Text.Encoding]::UTF8)
$helperCode=[regex]::Match($helperScript,"(?s)Add-Type -TypeDefinition @'\r?\n(.*?)\r?\n'@").Groups[1].Value
if(-not $helperCode){throw 'Text formatting helper not found'}
Add-Type -TypeDefinition $helperCode
$sourcePath=Join-Path $PSScriptRoot 'source_current.docx'
$outputPath=Join-Path $PSScriptRoot 'Bao_cao_Vu_Mon_Tranh_Hung_40_credit.docx'
$zip=[IO.Compression.ZipFile]::OpenRead($sourcePath)
$reader=[IO.StreamReader]::new($zip.GetEntry('word/document.xml').Open())
$doc=[Xml.XmlDocument]::new()
$doc.PreserveWhitespace=$true
$doc.LoadXml($reader.ReadToEnd())
$reader.Dispose();$zip.Dispose()
$w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'
$xmlSpace='http://www.w3.org/XML/1998/namespace'
$ns=[Xml.XmlNamespaceManager]::new($doc.NameTable)
$ns.AddNamespace('w',$w)
$paragraphs=@($doc.SelectNodes('//w:body//w:p',$ns))
$body=$doc.SelectSingleNode('//w:body',$ns)
function Text-Of($p){return ($p.SelectNodes('.//w:t',$ns)|ForEach-Object {$_.InnerText}) -join ''}
function Format-Of($p){
    $clone=$p.CloneNode($true)
    foreach($t in @($clone.SelectNodes('.//w:t',$ns))){$t.InnerText='';$t.RemoveAttribute('space',$xmlSpace)}
    foreach($b in @($clone.SelectNodes('.//w:bookmarkStart|.//w:bookmarkEnd',$ns))){[void]$b.ParentNode.RemoveChild($b)}
    return $clone.OuterXml
}
$originalFormats=@{}
for($i=0;$i -lt $paragraphs.Count;$i++){$originalFormats[$i]=Format-Of $paragraphs[$i]}
function Count-Words {
    $text=($doc.SelectNodes('//w:body//w:p[not(descendant::w:p)]',$ns)|ForEach-Object {Text-Of $_}) -join ' '
    return [regex]::Matches($text,'\S+').Count
}
$beforeCount=Count-Words
$map=Get-Content (Join-Path $PSScriptRoot 'replacements.json') -Raw -Encoding UTF8|ConvertFrom-Json
foreach($property in $map.PSObject.Properties){
    $id=[int]$property.Name
    $p=$paragraphs[$id]
    if($p.SelectNodes('.//w:fldChar|.//w:instrText|.//w:drawing|.//w:pict',$ns).Count){throw "Special content at paragraph $id"}
    $nodes=@($p.SelectNodes('.//w:t',$ns))
    $pieces=[string[]]@($nodes|ForEach-Object {$_.InnerText})
    $new=[ReportText]::Redistribute($pieces,[string]$property.Value)
    for($j=0;$j -lt $nodes.Count;$j++){
        $nodes[$j].InnerText=$new[$j]
        if($new[$j] -match '^\s|\s$'){[void]$nodes[$j].SetAttribute('space',$xmlSpace,'preserve')}
    }
    if((Text-Of $p) -cne [string]$property.Value){throw "Text mismatch at $id"}
}
# Only repeated explanations are removed. Preserve sections, diagrams, tables, and all member information.
$removed=400,409,435,503,505,597,633,657,671,702,752,756,764,767,775,840,851,854,886,896,898,903,906,916,923,926,933,1103,1223,1266,1268,1270,1272,1290,1325
foreach($id in $removed){
    $p=$paragraphs[$id]
    if($p.ParentNode.LocalName -ne 'body' -or $p.SelectNodes('.//w:sectPr|.//w:drawing|.//w:pict|.//w:fldChar|.//w:instrText',$ns).Count){throw "Unsafe deletion: $id"}
    [void]$body.RemoveChild($p)
}
# Replace two repeated summary tables with the existing detailed evidence and a short GET-count note.
foreach($range in @(@(999,1025),@(1225,1248))){
    $node=$paragraphs[$range[0]]
    $stop=$paragraphs[$range[1]]
    while(-not [object]::ReferenceEquals($node,$stop)){
        if($null -eq $node -or $node.SelectNodes('.//w:sectPr|.//w:drawing|.//w:pict',$ns).Count){throw 'Unsafe redundant table block'}
        $next=$node.NextSibling
        [void]$body.RemoveChild($node)
        $node=$next
    }
    $removed+=($range[0]..($range[1]-1))
}
# The input no longer contains the old environment table; remove its stale index entry.
foreach($id in 345,347,353){[void]$body.RemoveChild($paragraphs[$id])}
$removed+=345,347,353
$bookmarkId=1+(($doc.SelectNodes('//w:bookmarkStart',$ns)|ForEach-Object {[int]$_.GetAttribute('id',$w)}|Measure-Object -Maximum).Maximum)
function Add-Bookmark($p,[string]$name){
    $script:bookmarkId++
    $start=$doc.CreateElement('w','bookmarkStart',$w)
    [void]$start.SetAttribute('id',$w,[string]$script:bookmarkId)
    [void]$start.SetAttribute('name',$w,$name)
    $end=$doc.CreateElement('w','bookmarkEnd',$w)
    [void]$end.SetAttribute('id',$w,[string]$script:bookmarkId)
    $pPr=$p.SelectSingleNode('./w:pPr',$ns)
    if($pPr){[void]$p.InsertAfter($start,$pPr)}else{[void]$p.PrependChild($start)}
    [void]$p.AppendChild($end)
}
function Set-IndexLink($row,[string]$name,[string]$title){
    $link=$row.SelectSingleNode('./w:hyperlink',$ns)
    if($null -eq $link){throw 'Missing index hyperlink'}
    [void]$link.SetAttribute('anchor',$w,$name)
    $texts=@($link.SelectNodes('./w:r[w:t]/w:t',$ns))
    if($texts.Count -ne 2){throw 'Unexpected index format'}
    $texts[0].InnerText=$title
    $texts[1].InnerText='1'
    $link.SelectSingleNode('.//w:instrText',$ns).InnerText=' PAGEREF '+$name+' \h '
}
Add-Bookmark $paragraphs[1373] 'Credit40_MemberTable'
Set-IndexLink $paragraphs[358] 'Credit40_MemberTable' (Text-Of $paragraphs[1373])
foreach($pair in @(@(346,936),@(348,1026),@(349,1063),@(350,1105),@(351,1144),@(352,1184),@(354,1250))){
    $row=$paragraphs[$pair[0]]
    $name=$row.SelectSingleNode('./w:hyperlink',$ns).GetAttribute('anchor',$w)
    Set-IndexLink $row $name (Text-Of $paragraphs[$pair[1]])
}
# Add the current member appendix and its commit screenshots to the existing lists using their original formats.
Add-Bookmark $paragraphs[1372] 'Credit40_Members'
$row=$paragraphs[303].CloneNode($true)
Set-IndexLink $row 'Credit40_Members' 'F Phan cong thanh vien'
# Take the actual Vietnamese title from the document, with a space after the appendix letter.
$title=(Text-Of $paragraphs[1372]) -replace '^F(?=\S)','F '
Set-IndexLink $row 'Credit40_Members' $title
[void]$body.InsertBefore($row,$paragraphs[305])
foreach($id in 1395,1397){
    $name='Credit40_Figure_'+$id
    Add-Bookmark $paragraphs[$id] $name
    $row=$paragraphs[369].CloneNode($true)
    Set-IndexLink $row $name (Text-Of $paragraphs[$id])
    [void]$body.InsertBefore($row,$paragraphs[370])
}
$checked=0
for($i=0;$i -lt $paragraphs.Count;$i++){
    if($i -in $removed -or $i -eq 358){continue}
    if((Format-Of $paragraphs[$i]) -cne $originalFormats[$i]){throw "Formatting changed: $i"}
    $checked++
}
$bookmarks=@($doc.SelectNodes('//w:bookmarkStart',$ns)|ForEach-Object {$_.GetAttribute('name',$w)})
$targets=@(foreach($link in $doc.SelectNodes('//w:hyperlink[starts-with(@w:anchor,"ShortReport_") or starts-with(@w:anchor,"Credit40_")]',$ns)){
    $name=$link.GetAttribute('anchor',$w)
    if($name -notin $bookmarks){throw "Missing index target: $name"}
    [pscustomobject]@{bookmark=$name;title=$link.SelectSingleNode('./w:r/w:t',$ns).InnerText}
})
$afterCount=Count-Words
Copy-Item -LiteralPath $sourcePath -Destination $outputPath -Force
$zip=[IO.Compression.ZipFile]::Open($outputPath,[IO.Compression.ZipArchiveMode]::Update)
$zip.GetEntry('word/document.xml').Delete()
$entry=$zip.CreateEntry('word/document.xml',[IO.Compression.CompressionLevel]::Optimal)
$settings=[Xml.XmlWriterSettings]::new();$settings.Encoding=[Text.UTF8Encoding]::new($false)
$stream=$entry.Open();$writer=[Xml.XmlWriter]::Create($stream,$settings)
try{$doc.Save($writer)}finally{$writer.Dispose();$stream.Dispose();$zip.Dispose()}
$targets|ConvertTo-Json -Depth 4|Set-Content (Join-Path $PSScriptRoot 'index-targets.json') -Encoding UTF8
$stats=[pscustomobject]@{OriginalXmlWords=$beforeCount;RevisedXmlWords=$afterCount;EstimatedCredits=[math]::Ceiling($afterCount/250);RewrittenParagraphs=@($map.PSObject.Properties).Count;RemovedRedundantTables=2;ParagraphFormatsVerified=$checked;IndexTargets=$targets.Count;Output=$outputPath}
$stats|ConvertTo-Json|Set-Content (Join-Path $PSScriptRoot 'verification.json') -Encoding UTF8
$stats|ConvertTo-Json
