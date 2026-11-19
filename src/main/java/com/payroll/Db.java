package com.payroll;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class Db {
  static Connection get() throws SQLException {
    Connection c = DriverManager.getConnection("jdbc:sqlite:payroll.db");
    try (Statement s = c.createStatement()) { s.execute("PRAGMA foreign_keys=ON"); }
    return c;
  }
  static void bind(PreparedStatement ps, Object[] a) throws SQLException {
    for (int i = 0; i < a.length; i++) ps.setObject(i + 1, a[i] instanceof BigDecimal b ? b.toPlainString() : a[i]);
  }
  static List<Map<String,Object>> q(Connection c, String sql, Object... a) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, a);
      ResultSet rs = ps.executeQuery(); ResultSetMetaData m = rs.getMetaData();
      List<Map<String,Object>> out = new ArrayList<>();
      while (rs.next()) { Map<String,Object> r = new LinkedHashMap<>();
        for (int i = 1; i <= m.getColumnCount(); i++) r.put(m.getColumnLabel(i), rs.getObject(i));
        out.add(r); }
      return out;
    }
  }
  static void x(Connection c, String sql, Object... a) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) { bind(ps, a); ps.executeUpdate(); }
  }
  static String hash(String pw) {
    try {
      byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
      return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(pbk(pw, salt));
    } catch (Exception e) { throw new RuntimeException(e); }
  }
  static boolean check(String pw, String stored) {
    try { String[] p = stored.split(":");
      return Arrays.equals(pbk(pw, Base64.getDecoder().decode(p[0])), Base64.getDecoder().decode(p[1]));
    } catch (Exception e) { return false; }
  }
  static byte[] pbk(String pw, byte[] salt) throws Exception {
    return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(pw.toCharArray(), salt, 65536, 256)).getEncoded();
  }
  static void ensureColumn(Connection c,String table,String column,String type) throws SQLException {
    for (Map<String,Object> row : q(c,"PRAGMA table_info("+table+")")) if (column.equals(row.get("name"))) return;
    x(c,"ALTER TABLE "+table+" ADD COLUMN "+column+" "+type);
  }
  static void init() throws Exception {
    try (Connection c = get()) {
      String[] ddl = {
        "CREATE TABLE IF NOT EXISTS admin_users(id INTEGER PRIMARY KEY, username TEXT UNIQUE NOT NULL, password_hash TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS employees(id INTEGER PRIMARY KEY AUTOINCREMENT, emp_code TEXT UNIQUE, name TEXT NOT NULL, email TEXT UNIQUE NOT NULL, department TEXT, designation TEXT, gender TEXT, phone TEXT, joining_date TEXT, address TEXT, status TEXT DEFAULT 'Active', username TEXT UNIQUE NOT NULL, password_hash TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS salaries(id INTEGER PRIMARY KEY AUTOINCREMENT, employee_id INTEGER NOT NULL REFERENCES employees(id), basic TEXT, hra TEXT, da TEXT, travel TEXT, medical TEXT, other TEXT, effective_date TEXT)",
        "CREATE TABLE IF NOT EXISTS bonuses(id INTEGER PRIMARY KEY AUTOINCREMENT, employee_id INTEGER NOT NULL REFERENCES employees(id), type TEXT, amount TEXT NOT NULL, bonus_date TEXT NOT NULL, description TEXT, processed INTEGER DEFAULT 0)",
        "CREATE TABLE IF NOT EXISTS loan_applications(id INTEGER PRIMARY KEY AUTOINCREMENT, employee_id INTEGER NOT NULL REFERENCES employees(id), amount TEXT NOT NULL, reason TEXT, emi TEXT, applied_date TEXT, status TEXT DEFAULT 'Pending')",
        "CREATE TABLE IF NOT EXISTS loans(id INTEGER PRIMARY KEY AUTOINCREMENT, application_id INTEGER REFERENCES loan_applications(id), employee_id INTEGER NOT NULL REFERENCES employees(id), amount TEXT, emi TEXT, balance TEXT, start_month TEXT, status TEXT)",
        "CREATE TABLE IF NOT EXISTS loan_repayments(id INTEGER PRIMARY KEY AUTOINCREMENT, loan_id INTEGER NOT NULL REFERENCES loans(id), month TEXT NOT NULL, amount TEXT, balance_after TEXT, UNIQUE(loan_id, month))",
        "CREATE TABLE IF NOT EXISTS deductions(id INTEGER PRIMARY KEY AUTOINCREMENT, employee_id INTEGER NOT NULL REFERENCES employees(id), type TEXT NOT NULL, amount TEXT NOT NULL, month TEXT NOT NULL, description TEXT, processed INTEGER DEFAULT 0)",
        "CREATE TABLE IF NOT EXISTS payroll(id INTEGER PRIMARY KEY AUTOINCREMENT, employee_id INTEGER NOT NULL REFERENCES employees(id), month TEXT NOT NULL, gross TEXT, bonus TEXT, loan_emi TEXT, pf TEXT, ptax TEXT, insurance TEXT, other_ded TEXT, total_earnings TEXT, total_deductions TEXT, net TEXT, status TEXT, processed_date TEXT, UNIQUE(employee_id, month))",
        "CREATE VIEW IF NOT EXISTS salary_history AS SELECT id,employee_id,basic,hra,da,travel,medical,other,effective_date FROM salaries",
        "CREATE INDEX IF NOT EXISTS idx_bonus_emp ON bonuses(employee_id)",
        "CREATE INDEX IF NOT EXISTS idx_ded_emp ON deductions(employee_id, month)"};
      for (String s : ddl) x(c, s);
      ensureColumn(c,"employees","gender","TEXT");
      ensureColumn(c,"employees","phone","TEXT");
      ensureColumn(c,"employees","joining_date","TEXT");
      ensureColumn(c,"employees","address","TEXT");
      if (q(c, "SELECT 1 FROM admin_users").isEmpty()) {
        x(c, "INSERT INTO admin_users(username,password_hash) VALUES('admin',?)", hash("admin123"));
        String[][] emps = {{"Priya Raman","priya@abc.com","Engineering","Developer","priya","emp123","40000"},
                           {"Arun Kumar","arun@abc.com","Finance","Accountant","arun","emp123","35000"}};
        for (String[] e : emps) {
          x(c, "INSERT INTO employees(name,email,department,designation,username,password_hash) VALUES(?,?,?,?,?,?)", e[0], e[1], e[2], e[3], e[4], hash(e[5]));
          int id = ((Number) q(c, "SELECT last_insert_rowid() id").get(0).get("id")).intValue();
          x(c, "UPDATE employees SET emp_code=? WHERE id=?", String.format("EMP%03d", id), id);
          BigDecimal b = new BigDecimal(e[6]);
          x(c, "INSERT INTO salaries(employee_id,basic,hra,da,travel,medical,other,effective_date) VALUES(?,?,?,?,?,?,?,date('now'))",
            id, b, b.multiply(new BigDecimal("0.4")), b.multiply(new BigDecimal("0.1")), new BigDecimal("1500"), new BigDecimal("1250"), BigDecimal.ZERO);
        }
        x(c, "INSERT INTO loan_applications(employee_id,amount,reason,emi,applied_date,status) VALUES(2,'12000','Medical','1000',date('now'),'Active')");
        x(c, "INSERT INTO loans(application_id,employee_id,amount,emi,balance,start_month,status) VALUES(1,2,'12000','1000','12000','2026-11','Active')");
      }
    }
  }
}
