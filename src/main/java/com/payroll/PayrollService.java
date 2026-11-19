package com.payroll;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.*;

public class PayrollService {
  static BigDecimal bd(Object o) { return o == null ? BigDecimal.ZERO : new BigDecimal(o.toString()); }

  static Map<String,Object> calc(Connection c, int emp, String month) throws Exception {
    var e = Db.q(c, "SELECT * FROM employees WHERE id=?", emp);
    if (e.isEmpty()) throw new Fail(404, "Employee not found");
    if (!"Active".equals(e.get(0).get("status"))) throw new Fail(400, "Payroll cannot be processed for an inactive employee");
    var sal = Db.q(c, "SELECT * FROM salaries WHERE employee_id=? AND effective_date<=? ORDER BY effective_date DESC,id DESC LIMIT 1", emp, month + "-31");
    if (sal.isEmpty()) throw new Fail(400, "No salary configured for employee");
    BigDecimal gross = BigDecimal.ZERO;
    for (String k : new String[]{"basic","hra","da","travel","medical","other"}) gross = gross.add(bd(sal.get(0).get(k)));
    BigDecimal bonus = BigDecimal.ZERO; List<Object> bonusIds = new ArrayList<>();
    for (var b : Db.q(c, "SELECT id,amount FROM bonuses WHERE employee_id=? AND processed=0 AND substr(bonus_date,1,7)=?", emp, month)) {
      bonus = bonus.add(bd(b.get("amount"))); bonusIds.add(b.get("id")); }
    BigDecimal pf = BigDecimal.ZERO, ptax = BigDecimal.ZERO, ins = BigDecimal.ZERO, oth = BigDecimal.ZERO; List<Object> dedIds = new ArrayList<>();
    for (var d : Db.q(c, "SELECT id,type,amount FROM deductions WHERE employee_id=? AND processed=0 AND month=?", emp, month)) {
      BigDecimal a = bd(d.get("amount")); dedIds.add(d.get("id"));
      switch (String.valueOf(d.get("type"))) {
        case "PF" -> pf = pf.add(a);
        case "Professional Tax" -> ptax = ptax.add(a);
        case "Insurance" -> ins = ins.add(a);
        default -> oth = oth.add(a);
      } }
    BigDecimal emi = BigDecimal.ZERO; List<Map<String,Object>> parts = new ArrayList<>();
    for (var l : Db.q(c, "SELECT * FROM loans WHERE employee_id=? AND status='Active'", emp)) {
      BigDecimal bal = bd(l.get("balance"));
      if (bal.signum() <= 0 || String.valueOf(l.get("start_month")).compareTo(month) > 0) continue;
      if (!Db.q(c, "SELECT 1 FROM loan_repayments WHERE loan_id=? AND month=?", l.get("id"), month).isEmpty()) continue;
      BigDecimal amt = bd(l.get("emi")).min(bal);
      emi = emi.add(amt);
      parts.add(Map.of("id", l.get("id"), "amount", amt, "balanceAfter", bal.subtract(amt)));
    }
    BigDecimal earn = gross.add(bonus), ded = emi.add(pf).add(ptax).add(ins).add(oth);
    if (earn.subtract(ded).signum() < 0) throw new Fail(400, "Total deductions cannot exceed gross earnings");
    Map<String,Object> r = new LinkedHashMap<>();
    r.put("employeeId", emp); r.put("name", e.get(0).get("name")); r.put("department", e.get(0).get("department"));
    r.put("designation", e.get(0).get("designation")); r.put("month", month);
    r.put("gross", gross); r.put("bonus", bonus); r.put("totalEarnings", earn);
    r.put("loanEmi", emi); r.put("pf", pf); r.put("ptax", ptax); r.put("insurance", ins); r.put("otherDeductions", oth);
    r.put("totalDeductions", ded); r.put("net", earn.subtract(ded));
    r.put("loanParts", parts); r.put("bonusIds", bonusIds); r.put("deductionIds", dedIds);
    return r;
  }

  static Map<String,Object> process(int emp, String month) throws Exception {
    try (Connection c = Db.get()) {
      c.setAutoCommit(false);
      try {
        if (!Db.q(c, "SELECT 1 FROM payroll WHERE employee_id=? AND month=?", emp, month).isEmpty())
          throw new Fail(409, "Payroll already processed for this employee and month");
        var r = calc(c, emp, month);
        Db.x(c, "INSERT INTO payroll(employee_id,month,gross,bonus,loan_emi,pf,ptax,insurance,other_ded,total_earnings,total_deductions,net,status,processed_date) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'Processed',date('now'))",
          emp, month, r.get("gross"), r.get("bonus"), r.get("loanEmi"), r.get("pf"), r.get("ptax"), r.get("insurance"),
          r.get("otherDeductions"), r.get("totalEarnings"), r.get("totalDeductions"), r.get("net"));
        for (Object id : (List<?>) r.get("bonusIds")) Db.x(c, "UPDATE bonuses SET processed=1 WHERE id=?", id);
        for (Object id : (List<?>) r.get("deductionIds")) Db.x(c, "UPDATE deductions SET processed=1 WHERE id=?", id);
        for (Object o : (List<?>) r.get("loanParts")) {
          Map<?,?> p = (Map<?,?>) o; BigDecimal left = (BigDecimal) p.get("balanceAfter");
          Db.x(c, "INSERT INTO loan_repayments(loan_id,month,amount,balance_after) VALUES(?,?,?,?)", p.get("id"), month, p.get("amount"), left);
          Db.x(c, "UPDATE loans SET balance=?, status=? WHERE id=?", left, left.signum() == 0 ? "Completed" : "Active", p.get("id"));
        }
        c.commit();
        return r;
      } catch (Exception ex) { c.rollback(); throw ex; }
    }
  }
}
