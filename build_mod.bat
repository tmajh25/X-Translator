@echo off
setlocal

if exist "C:\Program Files\Android\Android Studio\jbr\bin\javac.exe" (
    set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
)

echo Dang bien dich mod X-Translator bang Gradle...
echo JAVA_HOME = %JAVA_HOME%
echo.

call gradlew.bat build --no-build-cache

if %ERRORLEVEL% equ 0 (
    echo.
    echo ============================================
    echo BIEN DICH THANH CONG!
    echo File mod nam tai: build\libs\xtranslator-1.0.0.jar
    echo ============================================
) else (
    echo.
    echo ============================================
    echo BIEN DICH THAT BAI! Vui long kiem tra loi tren.
    echo ============================================
)

pause
