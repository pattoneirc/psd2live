@echo off
rem Builds render_example.exe twice, as C and as C++, against the runtime built with `cargo build --release`.
rem Run from a shell where cl.exe is set up, or this calls the Visual Studio 2022 Build Tools' vcvars64.bat.
setlocal
cd /d "%~dp0"
where cl >nul 2>nul || call "%ProgramFiles(x86)%\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul
set LIB_DIR=..\..\target\release
cl /nologo /W4 /WX /TC /I ..\..\include render_example.c /Fe:render_example_c.exe /link %LIB_DIR%\p2l_runtime.dll.lib || exit /b 1
cl /nologo /W4 /WX /TP /EHsc /I ..\..\include render_example.c /Fe:render_example_cpp.exe /link %LIB_DIR%\p2l_runtime.dll.lib || exit /b 1
copy /y %LIB_DIR%\p2l_runtime.dll . >nul
