@echo off
chcp 65001 >nul
REM ============================================================
REM  Wrong Question Book - Windows Build & Package Script
REM  编译 + 打包JAR + 打包EXE
REM  要求: JDK 14 或更高版本 (jpackage 需要 JDK 14+)
REM ============================================================

setlocal
set APP_NAME=WrongQuestionBook
set MAIN_CLASS=com.mxcloud.Main
set JAR_FILE=%APP_NAME%.jar

echo.
echo ============================================
echo   Step 1/3: Compiling Java sources...
echo ============================================
if not exist bin mkdir bin
javac -encoding UTF-8 -d bin com\mxcloud\*.java
if errorlevel 1 (
    echo [ERROR] Compilation failed.
    pause
    exit /b 1
)
echo [OK] Compilation successful.

echo.
echo ============================================
echo   Step 2/3: Creating executable JAR...
echo ============================================
REM 创建 MANIFEST.MF
echo Manifest-Version: 1.0 > MANIFEST.MF
echo Main-Class: %MAIN_CLASS% >> MANIFEST.MF
echo. >> MANIFEST.MF

cd bin
jar cfm ..\%JAR_FILE% ..\MANIFEST.MF com\mxcloud\*.class
cd ..
if errorlevel 1 (
    echo [ERROR] JAR creation failed.
    pause
    exit /b 1
)
echo [OK] %JAR_FILE% created.
echo.
echo   Test run: java -jar %JAR_FILE%
echo.

echo ============================================
echo   Step 3/3: Packaging Windows EXE...
echo ============================================
REM 检查 jpackage 是否可用 (JDK 14+)
where jpackage >nul 2>&1
if errorlevel 1 (
    echo [WARNING] jpackage not found. Requires JDK 14+.
    echo           Skipping EXE packaging. JAR file is ready.
    echo.
    echo   To package EXE later, install JDK 14+ and run:
    echo   jpackage --name %APP_NAME% --input . --main-jar %JAR_FILE% --main-class %MAIN_CLASS% --type exe --win-dir-chooser --win-menu
    goto :done
)

if exist %APP_NAME% rmdir /s /q %APP_NAME%
jpackage --name %APP_NAME% --input . --main-jar %JAR_FILE% --main-class %MAIN_CLASS% --type exe --win-dir-chooser --win-menu --win-shortcut
if errorlevel 1 (
    echo [WARNING] EXE packaging failed. JAR file is still ready.
    goto :done
)
echo [OK] EXE installer created: %APP_NAME%-1.0.exe

:done
echo.
echo ============================================
echo   Build Complete!
echo ============================================
echo   JAR file:  %JAR_FILE%
echo   Run with:  java -jar %JAR_FILE%
echo.
endlocal
pause
