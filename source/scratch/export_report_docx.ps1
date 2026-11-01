param(
    [string]$InputFile = 'report/bao-cao-so-bo.md',
    [string]$OutputFile = 'report/bao-cao-so-bo.docx'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Escape-Xml([string]$value) {
    return [System.Security.SecurityElement]::Escape($value)
}

function Plain-Inline([string]$value) {
    $value = [regex]::Replace($value, '\[([^\]]+)\]\(([^)]+)\)', '$1 ($2)')
    return $value.Replace('**', '').Replace('`', '')
}

function Paragraph([string]$value, [string]$style = 'Normal', [bool]$bold = $false) {
    $escaped = Escape-Xml $value
    $boldXml = if ($bold) { '<w:b/>' } else { '' }
    return '<w:p><w:pPr><w:pStyle w:val="' + $style + '"/></w:pPr><w:r><w:rPr>' + $boldXml + '</w:rPr><w:t xml:space="preserve">' + $escaped + '</w:t></w:r></w:p>'
}

$lines = @(Get-Content -LiteralPath $InputFile -Encoding UTF8)
$body = [System.Text.StringBuilder]::new()
$inCode = $false
for ($lineIndex = 0; $lineIndex -lt $lines.Count; $lineIndex++) {
    $line = $lines[$lineIndex]
    if ($line.StartsWith('```')) { $inCode = -not $inCode; continue }
    if ($inCode) { [void]$body.Append((Paragraph $line 'Code')); continue }
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    if ($line.StartsWith('|')) {
        $rows = [System.Collections.Generic.List[object]]::new()
        while ($lineIndex -lt $lines.Count -and $lines[$lineIndex].StartsWith('|')) {
            $tableLine = $lines[$lineIndex]
            if ($tableLine -notmatch '^\|[\s:|\-]+\|$') {
                $cells = @($tableLine.Trim().Trim('|').Split('|') | ForEach-Object { $_.Trim() })
                $rows.Add($cells)
            }
            $lineIndex++
        }
        $lineIndex--
        $columns = $rows[0].Count
        $cellWidth = [int][Math]::Floor(9332 / $columns)
        [void]$body.Append('<w:tbl><w:tblPr><w:tblW w:w="9332" w:type="dxa"/><w:tblBorders>')
        foreach ($edge in @('top','left','bottom','right','insideH','insideV')) {
            [void]$body.Append('<w:' + $edge + ' w:val="single" w:sz="4" w:color="C6D1DF"/>')
        }
        [void]$body.Append('</w:tblBorders><w:tblCellMar><w:top w:w="80" w:type="dxa"/><w:left w:w="100" w:type="dxa"/><w:bottom w:w="80" w:type="dxa"/><w:right w:w="100" w:type="dxa"/></w:tblCellMar></w:tblPr><w:tblGrid>')
        for ($c = 0; $c -lt $columns; $c++) { [void]$body.Append('<w:gridCol w:w="' + $cellWidth + '"/>') }
        [void]$body.Append('</w:tblGrid>')
        for ($r = 0; $r -lt $rows.Count; $r++) {
            [void]$body.Append('<w:tr>')
            if ($r -eq 0) { [void]$body.Append('<w:trPr><w:tblHeader/></w:trPr>') }
            foreach ($cell in $rows[$r]) {
                $shade = if ($r -eq 0) { '<w:shd w:fill="E8EFF7"/>' } else { '' }
                [void]$body.Append('<w:tc><w:tcPr><w:tcW w:w="' + $cellWidth + '" w:type="dxa"/>' + $shade + '</w:tcPr>')
                [void]$body.Append((Paragraph (Plain-Inline $cell) 'TableText' ($r -eq 0)))
                [void]$body.Append('</w:tc>')
            }
            [void]$body.Append('</w:tr>')
        }
        [void]$body.Append('</w:tbl>')
        [void]$body.Append((Paragraph '' 'Normal'))
        continue
    }
    if ($line -match '^(#{1,3}) (.*)$') {
        $style = switch ($Matches[1].Length) { 1 { 'Title' } 2 { 'Heading1' } 3 { 'Heading2' } }
        [void]$body.Append((Paragraph (Plain-Inline $Matches[2]) $style))
        continue
    }
    if ($line.StartsWith('- ')) { $line = [char]0x2022 + ' ' + $line.Substring(2) }
    [void]$body.Append((Paragraph (Plain-Inline $line)))
}
if ($inCode) { throw 'Unclosed Markdown code block.' }

$styles = @'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Times New Roman" w:hAnsi="Times New Roman" w:eastAsia="Times New Roman"/><w:sz w:val="24"/><w:lang w:val="vi-VN"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="100" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:pPr><w:jc w:val="center"/><w:spacing w:before="120" w:after="240"/><w:keepNext/></w:pPr><w:rPr><w:b/><w:sz w:val="32"/><w:color w:val="17365D"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="220" w:after="120"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:sz w:val="28"/><w:color w:val="17365D"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="150" w:after="80"/><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:b/><w:sz w:val="25"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Code"><w:name w:val="Code"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:before="0" w:after="0" w:line="240"/><w:shd w:fill="F4F6F8"/></w:pPr><w:rPr><w:rFonts w:ascii="Consolas" w:hAnsi="Consolas"/><w:sz w:val="18"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="TableText"><w:name w:val="Table Text"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="40" w:line="240"/></w:pPr><w:rPr><w:sz w:val="21"/></w:rPr></w:style>
</w:styles>
'@
$document = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><w:body>' + $body.ToString() + '<w:sectPr><w:footerReference w:type="default" r:id="rIdFooter"/><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1440" w:header="500" w:footer="500"/></w:sectPr></w:body></w:document>'
$parts = @{
    '[Content_Types].xml' = '<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/></Types>'
    '_rels/.rels' = '<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rIdDocument" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>'
    'word/document.xml' = $document
    'word/styles.xml' = $styles
    'word/_rels/document.xml.rels' = '<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/><Relationship Id="rIdFooter" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/></Relationships>'
    'word/footer1.xml' = '<?xml version="1.0" encoding="UTF-8"?><w:ftr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:t>Game Portal – Báo cáo sơ bộ | Trang </w:t></w:r><w:fldSimple w:instr="PAGE"/></w:p></w:ftr>'
}
# Validate XML before packaging; Word opens the same text as the Markdown source.
foreach ($part in $parts.Values) { $null = [xml]$part }
$absoluteOutput = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $OutputFile))
$outputStream = [System.IO.File]::Open($absoluteOutput, [System.IO.FileMode]::Create, [System.IO.FileAccess]::ReadWrite)
$zip = [System.IO.Compression.ZipArchive]::new($outputStream, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($name in $parts.Keys) {
        $entry = $zip.CreateEntry($name)
        $writer = [System.IO.StreamWriter]::new($entry.Open(), [System.Text.UTF8Encoding]::new($false))
        try { $writer.Write($parts[$name]) } finally { $writer.Dispose() }
    }
} finally { $zip.Dispose() }
$check = [System.IO.Compression.ZipFile]::OpenRead($absoluteOutput)
try {
    if ($check.Entries.Count -ne 6) { throw 'Unexpected DOCX package structure.' }
    foreach ($entry in $check.Entries) {
        $reader = [System.IO.StreamReader]::new($entry.Open())
        try { $null = [xml]$reader.ReadToEnd() } finally { $reader.Dispose() }
    }
} finally { $check.Dispose() }
Get-Item -LiteralPath $absoluteOutput | Select-Object Name, Length
