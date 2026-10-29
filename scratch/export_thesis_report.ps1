param([string]$OutputFile = 'report/bao-cao-theo-mau-kltn.docx')
$ErrorActionPreference = 'Stop'
& node (Join-Path $PSScriptRoot 'export_thesis_report.mjs')
if ($LASTEXITCODE -ne 0) { throw 'Cannot generate document parts.' }
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$stagePath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) 'scratch/thesis-docx'))
$outputPath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $OutputFile))
$outputStream = [System.IO.File]::Open($outputPath, [System.IO.FileMode]::Create, [System.IO.FileAccess]::ReadWrite)
$archive = [System.IO.Compression.ZipArchive]::new($outputStream, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($part in (Get-ChildItem -LiteralPath $stagePath -Recurse -File)) {
        $relativeName = $part.FullName.Substring($stagePath.Length + 1).Replace('\', '/')
        if ($relativeName -eq 'manifest.json') { continue }
        if ($part.Extension -in @('.xml', '.rels')) {
            $null = [xml][System.IO.File]::ReadAllText($part.FullName, [System.Text.Encoding]::UTF8)
        }
        $entry = $archive.CreateEntry($relativeName)
        $entryStream = $entry.Open()
        $sourceStream = [System.IO.File]::OpenRead($part.FullName)
        try { $sourceStream.CopyTo($entryStream) } finally { $sourceStream.Dispose(); $entryStream.Dispose() }
    }
} finally { $archive.Dispose() }

# Inspect the finished package, using the real values encoded in the Word file.
$check = [System.IO.Compression.ZipFile]::OpenRead($outputPath)
try {
    foreach ($entry in $check.Entries) {
        if ($entry.FullName.EndsWith('.xml') -or $entry.FullName.EndsWith('.rels')) {
            $reader = [System.IO.StreamReader]::new($entry.Open())
            try { $null = [xml]$reader.ReadToEnd() } finally { $reader.Dispose() }
        }
    }
    $reader = [System.IO.StreamReader]::new($check.GetEntry('word/document.xml').Open())
    try { [xml]$document = $reader.ReadToEnd() } finally { $reader.Dispose() }
    $ns = [System.Xml.XmlNamespaceManager]::new($document.NameTable)
    $ns.AddNamespace('w', 'http://schemas.openxmlformats.org/wordprocessingml/2006/main')
    $sections = $document.SelectNodes('//w:sectPr', $ns)
    if ($sections.Count -ne 4) { throw 'Expected two covers, front matter and main body sections.' }
    foreach ($section in $sections) {
        $margins = $section.SelectSingleNode('w:pgMar', $ns)
        foreach ($key in @('top','bottom','left','right')) {
            $expected = @{ top = '1701'; bottom = '1701'; left = '1984'; right = '1134' }[$key]
            if ($margins.GetAttribute($key, $ns.LookupNamespace('w')) -ne $expected) { throw ('Incorrect margin: ' + $key) }
        }
    }
    $text = ($document.SelectNodes('//w:body//w:t', $ns) | ForEach-Object { $_.InnerText }) -join "`n"
    if ($text.Contains([char]0xFFFD)) { throw 'Broken Unicode text.' }
    foreach ($required in @('CHƯƠNG 1.', 'CHƯƠNG 5.', '574', '46', '35', 'submit_arena_answer')) {
        if (-not $text.Contains($required)) { throw ('Missing content: ' + $required) }
    }
    if ($text.Contains('@@CONTENTS@@')) { throw 'Unexpanded contents field.' }
    'Verified: A4 margins, two covers, Roman/Arabic sections, Unicode and report content.'
} finally { $check.Dispose() }
Get-Item -LiteralPath $outputPath | Select-Object Name, Length
