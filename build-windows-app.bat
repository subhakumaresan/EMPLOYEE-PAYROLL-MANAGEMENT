@echo off
setlocal
cd /d "%~dp0"
echo ================================================
echo  Employee Payroll Management - Windows Build
echo ================================================
echo.
where java >nul 2>nul || (echo Java 17+ not found.&pause&exit /b 1)
where mvn >nul 2>nul || (echo Maven not found.&pause&exit /b 1)
call mvn -DskipTests package
if errorlevel 1 (echo Maven build failed.&pause&exit /b 1)
if not exist target\app mkdir target\app
if exist target\app\EmployeePayrollManagement.exe del /q target\app\EmployeePayrollManagement.exe
jpackage --name "Employee Payroll Management" --input target --main-jar employee-payroll-system-1.1.0.jar --main-class com.payroll.Main --type app-image --dest target\app --app-version 1.1.0 --vendor "Employee Payroll Management"
if errorlevel 1 (echo jpackage failed. Make sure you are using a JDK 17+ (not only a JRE).&pause&exit /b 1)
echo.
echo DONE. Open target\app\Employee Payroll Management\Employee Payroll Management.exe
echo You can create a desktop shortcut to that EXE for your demonstration.
pause
