@echo off
setlocal
cd /d "%~dp0"
if not exist target\classes\com\payroll\Main.class (
    echo First run: compiling Employee Payroll Management...
    call mvn compile
    if errorlevel 1 (
        echo.
        echo Build failed. Make sure Java 17+ and Maven are installed.
        pause
        exit /b 1
    )
)
echo Starting Employee Payroll Management...
call mvn exec:java
pause
