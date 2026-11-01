$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Text;
using System.Text.RegularExpressions;
public static class ReportText {
    public static string[] Redistribute(string[] pieces, string replacement) {
        string source=String.Concat(pieces);
        MatchCollection a=Regex.Matches(source,@"\S+|\s+"), b=Regex.Matches(replacement,@"\S+|\s+");
        int[,] dp=new int[a.Count+1,b.Count+1];
        for(int i=a.Count-1;i>=0;i--) for(int j=b.Count-1;j>=0;j--)
            dp[i,j]=a[i].Value==b[j].Value?dp[i+1,j+1]+1:Math.Max(dp[i+1,j],dp[i,j+1]);
        int[] aligned=new int[b.Count]; for(int j=0;j<aligned.Length;j++) aligned[j]=-1;
        for(int i=0,j=0;i<a.Count && j<b.Count;) {
            if(a[i].Value==b[j].Value){aligned[j]=i;i++;j++;}
            else if(dp[i+1,j]>=dp[i,j+1])i++;else j++;
        }
        int[] owners=new int[a.Count];
        for(int i=0,k=0,end=pieces.Length>0?pieces[0].Length:0;i<a.Count;i++) {
            while(k<pieces.Length-1 && a[i].Index>=end){k++;end+=pieces[k].Length;}
            owners[i]=k;
        }
        StringBuilder[] output=new StringBuilder[pieces.Length];
        for(int k=0;k<output.Length;k++)output[k]=new StringBuilder();
        int previous=-1, previousOwner=0;
        for(int j=0;j<b.Count;j++) {
            int index=aligned[j];
            if(index<0)index=Math.Min(previous+1,a.Count-1);
            int owner=a.Count>0?owners[Math.Max(0,index)]:0;
            owner=Math.Max(previousOwner,owner);
            output[owner].Append(b[j].Value); previousOwner=owner;
            if(aligned[j]>=0)previous=aligned[j];
        }
        string[] result=new string[pieces.Length];
        for(int k=0;k<result.Length;k++)result[k]=output[k].ToString();
        return result;
    }
}
'@
$root = $PSScriptRoot
$inputPath = Join-Path $root 'Khung_bao_cao_Vu_Mon_Tranh_Hung.original.docx'
$outputPath = Join-Path $root 'Bao_cao_Vu_Mon_Tranh_Hung_rut_gon.docx'
$w = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
$xmlSpace = 'http://www.w3.org/XML/1998/namespace'
function Read-Part($archive, [string]$part) {
    $reader = [IO.StreamReader]::new($archive.GetEntry($part).Open())
    try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
}
$sourceZip = [IO.Compression.ZipFile]::OpenRead($inputPath)
$doc = [Xml.XmlDocument]::new()
$doc.PreserveWhitespace = $true
$doc.LoadXml((Read-Part $sourceZip 'word/document.xml'))
$sourceZip.Dispose()
$ns = [Xml.XmlNamespaceManager]::new($doc.NameTable)
$ns.AddNamespace('w', $w)
$paragraphs = @($doc.SelectNodes('//w:body//w:p', $ns))
$body = $doc.SelectSingleNode('//w:body', $ns)
$replacements = Get-Content (Join-Path $root 'replacements.json') -Encoding UTF8 -Raw | ConvertFrom-Json
function Get-Text($paragraph) {
    return ($paragraph.SelectNodes('.//w:t', $ns) | ForEach-Object {$_.InnerText}) -join ''
}
function Format-Signature($paragraph) {
    $clone = $paragraph.CloneNode($true)
    foreach($t in @($clone.SelectNodes('.//w:t', $ns))) {
        $t.InnerText = ''
        $t.RemoveAttribute('space', $xmlSpace)
    }
    foreach($b in @($clone.SelectNodes('.//w:bookmarkStart|.//w:bookmarkEnd', $ns))) {
        [void]$b.ParentNode.RemoveChild($b)
    }
    return $clone.OuterXml
}
$originalSignatures = @{}
for($i=355; $i -lt $paragraphs.Count; $i++) {
    $originalSignatures[$i] = Format-Signature $paragraphs[$i]
}
$beforeWords = ([regex]::Matches((($paragraphs[355..($paragraphs.Count-1)] | ForEach-Object {Get-Text $_}) -join ' '), '\S+')).Count
foreach($property in $replacements.PSObject.Properties) {
    $id = [int]$property.Name
    $p = $paragraphs[$id]
    if($p.SelectNodes('.//w:instrText|.//w:fldChar|.//w:drawing|.//w:pict', $ns).Count) {
        throw "Unsafe replacement target: $id"
    }
    $nodes = @($p.SelectNodes('.//w:t', $ns))
    $pieces = [string[]]@($nodes | ForEach-Object {$_.InnerText})
    $newPieces = [ReportText]::Redistribute($pieces, [string]$property.Value)
    for($j=0; $j -lt $nodes.Count; $j++) {
        $nodes[$j].InnerText = $newPieces[$j]
        if($newPieces[$j] -match '^\s|\s$') {
            [void]$nodes[$j].SetAttribute('space', $xmlSpace, 'preserve')
        }
    }
    if((Get-Text $p) -cne [string]$property.Value) { throw "Replacement mismatch at $id" }
}
# Remove the superseded experimental chapter as whole body elements; no section properties are in it.
$oldStart = $paragraphs[908]
$newStart = $paragraphs[1067]
$node = $oldStart
$removedElements = 0
while(-not [object]::ReferenceEquals($node, $newStart)) {
    if($null -eq $node) { throw 'New experimental chapter not found' }
    if($node.SelectNodes('.//w:sectPr', $ns).Count) { throw 'Refusing to remove section formatting' }
    $next = $node.NextSibling
    [void]$body.RemoveChild($node)
    $node = $next
    $removedElements++
}
# A second literal title immediately repeats the Chapter V heading.
[void]$paragraphs[756].ParentNode.RemoveChild($paragraphs[756])

# Build accurate contents and figure/table lists using the existing index paragraph formats.
$bookmarkNext = 1 + (($doc.SelectNodes('//w:bookmarkStart', $ns) | ForEach-Object {[int]$_.GetAttribute('id',$w)} | Measure-Object -Maximum).Maximum)
$indexRows = [Collections.Generic.List[object]]::new()
function Add-TargetBookmark($paragraph, [int]$originalId) {
    $name = 'ShortReport_' + $originalId
    $script:bookmarkNext++
    $id = [string]$script:bookmarkNext
    $start = $doc.CreateElement('w','bookmarkStart',$w)
    [void]$start.SetAttribute('id',$w,$id)
    [void]$start.SetAttribute('name',$w,$name)
    $end = $doc.CreateElement('w','bookmarkEnd',$w)
    [void]$end.SetAttribute('id',$w,$id)
    $pPr = $paragraph.SelectSingleNode('./w:pPr',$ns)
    if($pPr) {[void]$paragraph.InsertAfter($start,$pPr)} else {[void]$paragraph.PrependChild($start)}
    [void]$paragraph.AppendChild($end)
    return $name
}
function Make-IndexRow($template, [string]$title, [string]$bookmark) {
    $p = $template.CloneNode($true)
    foreach($attr in @($p.Attributes)) {
        if($attr.LocalName -in 'paraId','textId') {[void]$p.RemoveAttributeNode($attr)}
    }
    # Keep pPr and the original hyperlink, including every run property.
    foreach($child in @($p.ChildNodes)) {
        if($child.LocalName -notin 'pPr','hyperlink') {[void]$p.RemoveChild($child)}
    }
    $link = $p.SelectSingleNode('./w:hyperlink',$ns)
    [void]$link.SetAttribute('anchor',$w,$bookmark)
    $titleNodes = @($link.SelectNodes('./w:r[w:t]/w:t',$ns))
    if($titleNodes.Count -ne 2) {throw 'Unexpected index template structure'}
    $titleNodes[0].InnerText = $title
    $titleNodes[1].InnerText = '1'
    $link.SelectSingleNode('.//w:instrText',$ns).InnerText = ' PAGEREF '+$bookmark+' \h '
    $indexRows.Add([pscustomobject]@{bookmark=$bookmark;title=$title})
    return ,$p
}
function Replace-Index([int]$first, [int]$last, $entries, [int]$levelOneTemplate, [int]$levelTwoTemplate) {
    $firstOld = $paragraphs[$first]
    $openingRuns = @($firstOld.SelectNodes('./w:r',$ns) | ForEach-Object {$_.CloneNode($true)})
    $isFirst = $true
    foreach($entry in $entries) {
        $templateId = if($entry.level -eq 2) {$levelTwoTemplate} else {$levelOneTemplate}
        $row = Make-IndexRow $paragraphs[$templateId] $entry.title $entry.bookmark
        if($isFirst) {
            $link=$row.SelectSingleNode('./w:hyperlink',$ns)
            foreach($run in $openingRuns) {[void]$row.InsertBefore($run,$link)}
            $isFirst = $false
        }
        [void]$body.InsertBefore($row,$firstOld)
    }
    for($i=$first;$i -le $last;$i++) {[void]$body.RemoveChild($paragraphs[$i])}
}
$mainEntries = [Collections.Generic.List[object]]::new()
for($i=362;$i -lt $paragraphs.Count;$i++) {
    if($i -ge 908 -and $i -lt 1067) {continue}
    $p=$paragraphs[$i]
    $style=$p.SelectSingleNode('./w:pPr/w:pStyle',$ns)
    if($null -eq $style) {continue}
    $styleName=$style.GetAttribute('val',$w)
    if($styleName -notin 'u1','u2') {continue}
    $title=Get-Text $p
    $title=$title -replace '^(CHƯƠNG\s+[IVX]+)(?=[A-ZÀ-Ỹ])(?![IVX])','$1 '
    if($i -eq 363) {$title='1.1 '+$title}
    $mainEntries.Add([pscustomobject]@{title=$title;bookmark=(Add-TargetBookmark $p $i);level=if($styleName -eq 'u2'){2}else{1}})
}
$tableIds=417,468,489,514,533,596,617,688,759,845,1070,1104,1167,1194,1231,1273,1312,1352,1393,1418,1442,1497,1520,1542
$figureIds=584,666,737,836,1270,1344,1346,1348,1390
$tableEntries=@(foreach($i in $tableIds){[pscustomobject]@{title=(Get-Text $paragraphs[$i]);bookmark=(Add-TargetBookmark $paragraphs[$i] $i);level=1}})
$figureEntries=@(foreach($i in $figureIds){[pscustomobject]@{title=(Get-Text $paragraphs[$i]);bookmark=(Add-TargetBookmark $paragraphs[$i] $i);level=1}})
Replace-Index 262 303 $mainEntries 268 263
Replace-Index 334 346 $tableEntries 345 345
Replace-Index 350 352 $figureEntries 352 352

$formatChecked=0
for($i=355;$i -lt $paragraphs.Count;$i++) {
    if(($i -ge 908 -and $i -lt 1067) -or $i -eq 756) {continue}
    if((Format-Signature $paragraphs[$i]) -cne $originalSignatures[$i]) {throw "Formatting changed at paragraph $i"}
    $formatChecked++
}
$afterText=($doc.SelectNodes('//w:body//w:p',$ns) | Where-Object { $null -eq $_.SelectSingleNode('./w:pPr/w:pStyle[@w:val="Mucluc1" or @w:val="Mucluc20"]',$ns) } | ForEach-Object {Get-Text $_}) -join ' '
$afterWords=([regex]::Matches((($paragraphs[355..($paragraphs.Count-1)] | Where-Object {$null -ne $_.ParentNode -and ($_.ParentNode.LocalName -eq 'body' -or $_.ParentNode.LocalName -eq 'tc')} | ForEach-Object {Get-Text $_}) -join ' '), '\S+')).Count
# Count only retained body paragraphs by original indices (including paragraphs inside retained tables).
$afterWords=([regex]::Matches(($(@(for($i=355;$i -lt $paragraphs.Count;$i++){if(($i -lt 908 -or $i -ge 1067) -and $i -ne 756){Get-Text $paragraphs[$i]}})) -join ' '), '\S+')).Count
Copy-Item -LiteralPath $inputPath -Destination $outputPath -Force
$zip = [IO.Compression.ZipFile]::Open($outputPath,[IO.Compression.ZipArchiveMode]::Update)
$zip.GetEntry('word/document.xml').Delete()
$newEntry=$zip.CreateEntry('word/document.xml',[IO.Compression.CompressionLevel]::Optimal)
$stream=$newEntry.Open()
$settings=[Xml.XmlWriterSettings]::new()
$settings.Encoding=[Text.UTF8Encoding]::new($false)
$settings.Indent=$false
$writer=[Xml.XmlWriter]::Create($stream,$settings)
try {$doc.Save($writer)} finally {$writer.Dispose();$stream.Dispose();$zip.Dispose()}
$indexRows | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $root 'index-targets.json') -Encoding UTF8
[pscustomobject]@{
    Output=$outputPath;OriginalContentWords=$beforeWords;RevisedContentWords=$afterWords
    ReductionPercent=[math]::Round(100*(1-$afterWords/$beforeWords),1)
    ParagraphsRewritten=@($replacements.PSObject.Properties).Count
    RetainedParagraphFormatsChecked=$formatChecked;RemovedSupersededBodyElements=$removedElements
    ContentsEntries=$mainEntries.Count;TableEntries=$tableEntries.Count;FigureEntries=$figureEntries.Count
} | ConvertTo-Json | Set-Content (Join-Path $root 'verification.json') -Encoding UTF8
Get-Content (Join-Path $root 'verification.json') -Raw -Encoding UTF8
