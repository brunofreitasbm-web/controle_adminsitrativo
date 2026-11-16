@echo off
rem Cria um atalho "Assinar PDF" na Area de Trabalho apontando para este programa.
powershell -NoProfile -Command "$s=(New-Object -ComObject WScript.Shell).CreateShortcut([Environment]::GetFolderPath('Desktop')+'\Assinar PDF.lnk'); $s.TargetPath='%~dp0Assinar PDF.exe'; $s.WorkingDirectory='%~dp0'; $s.Save()"
echo Atalho criado na Area de Trabalho.
pause
