# Employee Payroll Management System

A Java 17 web application for managing employee payroll. It has a responsive
HTML/CSS/JavaScript dashboard, SQLite storage, separate Admin and Employee
roles, an optional loan workflow, monthly payroll processing, and PDF/CSV
reports.

## Features

### Admin
- Dashboard with charts for employee distribution and monthly processed payroll
- Add, edit and deactivate employees (department, designation, contact details, joining date)
- Configure salary components and view an append-only salary revision history
- Add bonuses and employee-specific deductions
- Review loan applications and approve or reject them. Approved loans become
  active with the approved amount, agreed EMI and start month
- Calculate and process monthly payroll
- Preview monthly totals: employee count, gross payroll, bonuses, deductions and net payroll
- Download a multi-page PDF payroll register, a monthly CSV export and individual PDF payslips

### Employee
- Personal dashboard and current salary structure
- View own bonus, deduction and payroll history
- Apply for an optional loan and track application and repayment status
- Download own processed PDF payslips

### Loan deduction rules
- No application, a pending application or a rejected application means no loan deduction
- Only approved, active loans deduct the monthly EMI during payroll

## Tech Stack

| Layer      | Technology                                   |
|------------|----------------------------------------------|
| Backend    | Java 17, built-in `com.sun.net.httpserver`   |
| Database   | SQLite                                       |
| JSON       | Gson                                         |
| Frontend   | HTML, CSS, vanilla JavaScript                |
| Build tool | Maven                                        |

## Highlights
- Payroll calculations use `BigDecimal` for accurate money handling
- Database transactions and a unique employee/month constraint prevent duplicate payroll runs
- Role-based access: admin and employee views are separated
- Seed data is inserted only on first run, and the database is not reset on restart

## Requirements
- JDK 17 or newer
- Apache Maven 3.8 or newer
- Internet connection on the first build, so Maven can download dependencies
- A modern browser (Chrome, Edge or Firefox)

## Getting Started

```bash
git clone https://github.com/subhakumaresan/employee-payroll-management.git
cd employee-payroll-management
mvn compile exec:java
```

Then open **http://localhost:8080** in your browser.

On Windows you can also double-click `run.bat` once Maven is on your PATH.

### Standalone Windows app
Run `build-windows-app.bat` once on a PC with JDK 17+ and Maven. It creates an
EXE app image under `target\app`, so you can launch the app without Maven afterwards.

## Demo Login
Demo credentials are created from the seed data on first run. Add your admin and
employee usernames here, and change them before any real use.

Sample data: employee Arun has an active ₹12,000 loan with a ₹1,000 monthly EMI
starting November 2026. Employee Priya has no loan.

## Project Structure

```
employee-payroll-system/
├── pom.xml
├── run.bat
├── build-windows-app.bat
├── TEST-CHECKLIST.md
└── src/main/
    ├── java/com/payroll/
    │   ├── Main.java            # HTTP server and API routes
    │   ├── PayrollService.java  # Payroll calculation logic
    │   ├── Db.java              # SQLite helpers and seed data
    │   └── Fail.java            # Error handling
    └── resources/static/
        └── index.html           # Frontend dashboard
```

## How to Test the Loan Workflow
1. Log in as an employee, open **My Loans** and submit a loan application.
2. Log in as admin, open **Loans** and approve or reject the pending application.
3. Run payroll for a month. Approved loans deduct the EMI, rejected ones do not.

## Disclaimer
This is an educational project, not a production payroll or compliance system.
Do not use it with real employee data without proper security and legal review.

## License
MIT License
