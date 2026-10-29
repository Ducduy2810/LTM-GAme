Option Explicit
Dim app, doc, source, target
source = WScript.Arguments(0)
target = WScript.Arguments(1)
On Error Resume Next
Set app = CreateObject("Word.Application")
If Err.Number <> 0 Then
  WScript.Echo "Word creation failed: " & Err.Description
  WScript.Quit 1
End If
app.Visible = False
app.DisplayAlerts = 0
If Err.Number <> 0 Then
  WScript.Echo "Word automation failed: " & Err.Description
  app.Quit 0
  WScript.Quit 2
End If
Set doc = app.Documents.Open(source, False, False, False)
If Err.Number <> 0 Then
  WScript.Echo "Open failed: " & Err.Description
  app.Quit 0
  WScript.Quit 3
End If
doc.Fields.Update
Dim toc
For Each toc In doc.TablesOfContents
  toc.Update
Next
doc.Repaginate
doc.Save
doc.ExportAsFixedFormat target, 17
If Err.Number <> 0 Then
  WScript.Echo "Export failed: " & Err.Description
  doc.Close 0
  app.Quit 0
  WScript.Quit 4
End If
WScript.Echo "Pages: " & doc.ComputeStatistics(2)
doc.Close 0
app.Quit 0
