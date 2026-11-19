# Manual acceptance test checklist

Run the application, sign in with the demo accounts, and check each scenario.

1. **Employee without loan:** Sign in as Priya. Confirm no loan is listed. Admin calculates payroll for Priya; loan EMI must be ₹0.
2. **Pending loan:** Sign in as Priya, apply for a loan, then check Admin → Loans. Before admin approval, payroll loan EMI must remain ₹0.
3. **Rejected loan:** Reject the pending request. Confirm the employee application shows Rejected and payroll loan EMI remains ₹0.
4. **Approved loan:** Submit a new application and approve it with an approved amount and EMI. Process payroll for an eligible month. Confirm EMI is deducted and repayment history is created.
5. **Balance below EMI:** Use a loan balance smaller than the monthly EMI in a test database. Confirm only the remaining balance is deducted and the loan becomes Completed.
6. **Bonus without loan:** Add a bonus to an employee with no active loan. Confirm the bonus is included and loan EMI remains ₹0.
7. **Duplicate payroll:** Process the same employee/month twice. The second attempt must be rejected.
8. **Individual PDF:** Open Reports and download a payslip for a processed employee/month. Confirm it opens as a PDF and includes earnings, deductions and net salary.
9. **Monthly exports:** Download monthly CSV and PDF reports. Confirm the selected month's processed payroll records appear.
10. **Persistence:** Stop the server, restart it, and confirm employees, loans, repayments and payroll records remain available.

Do not test scenario 5 on the only copy of your database. Make a backup of `payroll.db` first.
