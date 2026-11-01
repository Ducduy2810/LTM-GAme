Option Explicit
Dim fso, root, app, original, doc, targets, text, regex, matches, m, output, originalPages, pages
Set fso = CreateObject("Scripting.FileSystemObject")
root = fso.GetParentFolderName(WScript.ScriptFullName)
Set app = CreateObject("Word.Application")
app.Visible = False
app.DisplayAlerts = 0
app.AutomationSecurity = 3
If WScript.Arguments.Count = 0 Then
    Set original = app.Documents.Open(root & "\Khung_bao_cao_Vu_Mon_Tranh_Hung.original.docx", False, True)
    original.Repaginate
    originalPages = original.ComputeStatistics(2)
    original.Close 0
End If
Set doc = app.Documents.Open(root & "\Bao_cao_Vu_Mon_Tranh_Hung_rut_gon.docx", False, True)
doc.Repaginate
pages = doc.ComputeStatistics(2)
If WScript.Arguments.Count > 0 Then
    doc.ExportAsFixedFormat root & "\Bao_cao_Vu_Mon_Tranh_Hung_rut_gon.pdf", 17
    WScript.Echo "PDF_PAGES=" & pages
Else
    Set targets = fso.OpenTextFile(root & "\index-targets.json", 1, False)
    text = targets.ReadAll
    targets.Close
    Set regex = New RegExp
    regex.Pattern = """bookmark""\s*:\s*""([^""]+)"""
    regex.Global = True
    Set matches = regex.Execute(text)
    Set output = fso.CreateTextFile(root & "\pagination.tsv", True, False)
    output.WriteLine "ORIGINAL_PAGES" & vbTab & originalPages
    output.WriteLine "REVISED_PAGES" & vbTab & pages
    doc.Bookmarks.ShowHidden = True
    For Each m In matches
        If Not doc.Bookmarks.Exists(m.SubMatches(0)) Then
            doc.Close 0
            app.Quit 0
            WScript.Echo "MISSING_BOOKMARK=" & m.SubMatches(0)
            WScript.Quit 1
        End If
        output.WriteLine m.SubMatches(0) & vbTab & doc.Bookmarks.Item(m.SubMatches(0)).Range.Information(1)
    Next
    output.Close
    WScript.Echo "ORIGINAL_PAGES=" & originalPages
    WScript.Echo "REVISED_PAGES=" & pages
    WScript.Echo "INDEX_ENTRIES=" & matches.Count
End If
doc.Close 0
app.Quit 0
