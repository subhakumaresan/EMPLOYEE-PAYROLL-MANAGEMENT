package com.payroll;
import com.google.gson.Gson;
import com.sun.net.httpserver.*;
import java.io.*;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Main {
  record Download(byte[] bytes, String filename, String contentType) {}
  static final Gson G = new Gson();
  static final Map<String,Map<String,Object>> sessions = new ConcurrentHashMap<>();

  public static void main(String[] args) throws Exception {
    Db.init();
    HttpServer s = HttpServer.create(new InetSocketAddress(8080), 0);
    s.createContext("/", Main::handle);
    s.start();
    System.out.println("Payroll running at http://localhost:8080");
  }

  static void handle(HttpExchange ex) throws IOException {
    String p = ex.getRequestURI().getPath();
    try {
      if (!p.startsWith("/api/")) { file(ex, p.equals("/") ? "/index.html" : p); return; }
      Object r = route(ex, p, session(ex));
      if (r instanceof Download d) {
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + d.filename + "\"");
        byte[] bytes = d.bytes;
        ex.getResponseHeaders().set("Content-Type", d.contentType);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(bytes); }
      } else if (r instanceof String csv) {
        ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=payroll.csv");
        send(ex, 200, csv, "text/csv; charset=utf-8");
      } else send(ex, 200, G.toJson(r), "application/json");
    } catch (Fail f) { send(ex, f.code, G.toJson(Map.of("error", f.getMessage())), "application/json");
    } catch (Exception e) { e.printStackTrace();
      String m = String.valueOf(e.getMessage());
      send(ex, 500, G.toJson(Map.of("error", m.contains("UNIQUE") ? "Duplicate value (email, username or record already exists)" : "Server error")), "application/json"); }
  }

  static Map<String,Object> session(HttpExchange ex) {
    String ck = ex.getRequestHeaders().getFirst("Cookie");
    if (ck != null) for (String part : ck.split(";")) { part = part.trim(); if (part.startsWith("sid=")) return sessions.get(part.substring(4)); }
    return null;
  }
  static Map<String,Object> admin(Map<String,Object> s) {
    if (s == null) throw new Fail(401, "Please log in");
    if (!"admin".equals(s.get("role"))) throw new Fail(403, "Admin access required");
    return s;
  }
  static Map<String,Object> user(Map<String,Object> s) { if (s == null) throw new Fail(401, "Please log in"); return s; }
  static Map<String,Object> body(HttpExchange ex) throws IOException {
    Map<String,Object> m = G.fromJson(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), Map.class);
    return m == null ? new HashMap<>() : m;
  }
  static String str(Map<String,Object> m, String k) {
    Object o = m.get(k); String v = o == null ? "" : o.toString().trim();
    if (v.isEmpty()) throw new Fail(400, k + " is required");
    return v;
  }
  static BigDecimal money(Map<String,Object> m, String k, boolean required) {
    Object o = m.get(k);
    if (o == null || o.toString().isBlank()) { if (required) throw new Fail(400, k + " is required"); return BigDecimal.ZERO; }
    try { BigDecimal b = new BigDecimal(o.toString()); if (b.signum() < 0 || (required && b.signum() == 0)) throw new Fail(400, k + " must be positive"); return b; }
    catch (NumberFormatException e) { throw new Fail(400, k + " must be a number"); }
  }
  static String month(String m) { if (!m.matches("\\d{4}-(0[1-9]|1[0-2])")) throw new Fail(400, "Month must be YYYY-MM"); return m; }
  static String param(HttpExchange ex, String k) {
    String q = ex.getRequestURI().getQuery(); if (q == null) return "";
    for (String kv : q.split("&")) { String[] p = kv.split("=", 2); if (p[0].equals(k) && p.length > 1) return java.net.URLDecoder.decode(p[1], StandardCharsets.UTF_8); }
    return "";
  }

  static Object route(HttpExchange ex, String p, Map<String,Object> s) throws Exception {
    String key = ex.getRequestMethod() + " " + p;
    if (key.equals("POST /api/login")) return login(ex);
    if (key.equals("POST /api/logout")) { String ck = ex.getRequestHeaders().getFirst("Cookie"); if (ck != null && ck.contains("sid=")) sessions.remove(ck.substring(ck.indexOf("sid=") + 4).split(";")[0]); return Map.of("ok", true); }
    if (key.equals("GET /api/me")) return user(s);
    if (key.equals("PUT /api/employees") || key.equals("DELETE /api/employees")) throw new Fail(400, "Employee ID is required");
    if (p.matches("/api/employees/\\d+")) {
      admin(s); int id = Integer.parseInt(p.substring(p.lastIndexOf('/') + 1));
      try (Connection c = Db.get()) {
        if (ex.getRequestMethod().equals("PUT")) {
          Map<String,Object> b = body(ex);
          String email=str(b,"email"); if(!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw new Fail(400,"Invalid email");
          Db.x(c, "UPDATE employees SET name=?,email=?,department=?,designation=?,gender=?,phone=?,joining_date=?,address=?,status=? WHERE id=?",
            str(b,"name"), email, str(b,"department"), str(b,"designation"), String.valueOf(b.getOrDefault("gender","")), String.valueOf(b.getOrDefault("phone","")), String.valueOf(b.getOrDefault("joiningDate","")), String.valueOf(b.getOrDefault("address","")), str(b,"status"), id);
          if (b.containsKey("basic")) {
            Db.x(c, "INSERT INTO salaries(employee_id,basic,hra,da,travel,medical,other,effective_date) VALUES(?,?,?,?,?,?,?,?)",
              id, money(b,"basic",true), money(b,"hra",false), money(b,"da",false), money(b,"travel",false), money(b,"medical",false), money(b,"other",false), LocalDate.now().toString());
          }
          return Map.of("ok",true);
        }
        if (ex.getRequestMethod().equals("DELETE")) {
          boolean linked = !Db.q(c,"SELECT 1 FROM payroll WHERE employee_id=? UNION ALL SELECT 1 FROM bonuses WHERE employee_id=? UNION ALL SELECT 1 FROM loans WHERE employee_id=? UNION ALL SELECT 1 FROM deductions WHERE employee_id=? UNION ALL SELECT 1 FROM loan_applications WHERE employee_id=? LIMIT 1",id,id,id,id,id).isEmpty();
          if (linked) { Db.x(c,"UPDATE employees SET status='Inactive' WHERE id=?",id); return Map.of("ok",true,"message","Employee has transaction history and was safely deactivated"); }
          Db.x(c,"DELETE FROM salaries WHERE employee_id=?",id); Db.x(c,"DELETE FROM employees WHERE id=?",id); return Map.of("ok",true,"message","Employee deleted");
        }
      }
    }
    if (p.matches("/api/reports/payslip/\\d+")) {
      user(s); int id=Integer.parseInt(p.substring(p.lastIndexOf('/')+1));
      try (Connection c=Db.get()) {
        if ("employee".equals(s.get("role")) && ((Number)s.get("id")).intValue()!=id) throw new Fail(403,"You can only download your own payslip");
        String m=month(param(ex,"month")); return pdfPayslip(c,id,m);
      }
    }
    if (key.equals("GET /api/reports/monthly.pdf")) { admin(s); try(Connection c=Db.get()){ return pdfMonthly(c,month(param(ex,"month"))); } }
    try (Connection c = Db.get()) {
      switch (key) {
        case "GET /api/dashboard": admin(s);
          return Map.of("employees", Db.q(c, "SELECT COUNT(*) n FROM employees").get(0).get("n"),
            "active", Db.q(c, "SELECT COUNT(*) n FROM employees WHERE status='Active'").get(0).get("n"),
            "pendingLoans", Db.q(c, "SELECT COUNT(*) n FROM loan_applications WHERE status='Pending'").get(0).get("n"),
            "departments", Db.q(c, "SELECT department, COUNT(*) n FROM employees GROUP BY department"),
            "recentPayroll", Db.q(c, "SELECT p.month,e.name,p.net FROM payroll p JOIN employees e ON e.id=p.employee_id ORDER BY p.id DESC LIMIT 5"),
            "monthlyPayroll", Db.q(c, "SELECT month, SUM(CAST(net AS REAL)) total FROM payroll GROUP BY month ORDER BY month DESC LIMIT 6"),
            "loanBalance", Db.q(c, "SELECT COALESCE(SUM(CAST(balance AS REAL)),0) total FROM loans WHERE status='Active'").get(0).get("total"),
            "payrollCount", Db.q(c, "SELECT COUNT(*) n FROM payroll").get(0).get("n"));
        case "GET /api/employees": admin(s);
          return Db.q(c, "SELECT id,emp_code,name,email,department,designation,status,username FROM employees ORDER BY id");
        case "GET /api/salaries": { admin(s); return Db.q(c, "SELECT e.id employee_id,e.emp_code,e.name,s.basic,s.hra,s.da,s.travel,s.medical,s.other,s.effective_date FROM employees e LEFT JOIN salaries s ON s.id=(SELECT MAX(id) FROM salaries WHERE employee_id=e.id) ORDER BY e.id"); }
        case "GET /api/salary-history": { admin(s); return Db.q(c,"SELECT h.*,e.name,e.emp_code FROM salary_history h JOIN employees e ON e.id=h.employee_id ORDER BY h.employee_id,h.id DESC"); }
        case "POST /api/salaries": { admin(s); var b=body(ex); int eid=Integer.parseInt(str(b,"empId"));
          Db.x(c,"INSERT INTO salaries(employee_id,basic,hra,da,travel,medical,other,effective_date) VALUES(?,?,?,?,?,?,?,?)",eid,money(b,"basic",true),money(b,"hra",false),money(b,"da",false),money(b,"travel",false),money(b,"medical",false),money(b,"other",false),LocalDate.now().toString());
          return Map.of("ok",true); }
        case "GET /api/employee/profile": { user(s); return Db.q(c,"SELECT e.id,e.emp_code,e.name,e.email,e.department,e.designation,e.gender,e.phone,e.joining_date,e.address,e.status,s.basic,s.hra,s.da,s.travel,s.medical,s.other,s.effective_date FROM employees e LEFT JOIN salaries s ON s.id=(SELECT MAX(id) FROM salaries WHERE employee_id=e.id) WHERE e.id=?",s.get("id")); }
        case "POST /api/employees": { admin(s); var b = body(ex);
          String email = str(b, "email"); if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw new Fail(400, "Invalid email");
          String pw = str(b, "password"); if (pw.length() < 6) throw new Fail(400, "Password must be at least 6 characters");
          BigDecimal basic = money(b, "basic", true);
          c.setAutoCommit(false);
          try {
            Db.x(c, "INSERT INTO employees(name,email,department,designation,gender,phone,joining_date,address,username,password_hash) VALUES(?,?,?,?,?,?,?,?,?,?)", str(b, "name"), email, str(b, "department"), str(b, "designation"), String.valueOf(b.getOrDefault("gender","")), String.valueOf(b.getOrDefault("phone","")), String.valueOf(b.getOrDefault("joiningDate",LocalDate.now().toString())), String.valueOf(b.getOrDefault("address","")), str(b, "username"), Db.hash(pw));
            int id = ((Number) Db.q(c, "SELECT last_insert_rowid() id").get(0).get("id")).intValue();
            Db.x(c, "UPDATE employees SET emp_code=? WHERE id=?", String.format("EMP%03d", id), id);
            Db.x(c, "INSERT INTO salaries(employee_id,basic,hra,da,travel,medical,other,effective_date) VALUES(?,?,?,?,?,?,?,date('now'))", id, basic, money(b, "hra", false), money(b, "da", false), money(b, "travel", false), money(b, "medical", false), money(b, "other", false));
            c.commit(); return Map.of("id", id);
          } catch (Exception e) { c.rollback(); throw e; } }
        case "GET /api/bonuses": { user(s); if ("admin".equals(s.get("role"))) return Db.q(c, "SELECT b.*,e.name FROM bonuses b JOIN employees e ON e.id=b.employee_id ORDER BY b.id DESC"); return Db.q(c,"SELECT * FROM bonuses WHERE employee_id=? ORDER BY id DESC",s.get("id")); }
        case "POST /api/bonuses": { admin(s); var b = body(ex);
          Db.x(c, "INSERT INTO bonuses(employee_id,type,amount,bonus_date,description) VALUES(?,?,?,?,?)", Integer.parseInt(str(b, "empId")), str(b, "type"), money(b, "amount", true), str(b, "date"), String.valueOf(b.getOrDefault("description", "")));
          return Map.of("ok", true); }
        case "GET /api/deductions": { user(s); if ("admin".equals(s.get("role"))) return Db.q(c, "SELECT d.*,e.name FROM deductions d JOIN employees e ON e.id=d.employee_id ORDER BY d.id DESC"); return Db.q(c,"SELECT * FROM deductions WHERE employee_id=? ORDER BY id DESC",s.get("id")); }
        case "POST /api/deductions": { admin(s); var b = body(ex);
          Db.x(c, "INSERT INTO deductions(employee_id,type,amount,month,description) VALUES(?,?,?,?,?)", Integer.parseInt(str(b, "empId")), str(b, "type"), money(b, "amount", true), month(str(b, "month")), String.valueOf(b.getOrDefault("description", "")));
          return Map.of("ok", true); }
        case "GET /api/payroll/calc": admin(s);
          try { return PayrollService.calc(c, Integer.parseInt(param(ex, "empId")), month(param(ex, "month"))); } catch (NumberFormatException e) { throw new Fail(400, "Select an employee"); }
        case "POST /api/payroll/process": { admin(s); var b = body(ex);
          return PayrollService.process(Integer.parseInt(str(b, "empId")), month(str(b, "month"))); }
        case "GET /api/payroll":
          if ("admin".equals(user(s).get("role"))) return Db.q(c, "SELECT p.*,e.name,e.emp_code,e.department FROM payroll p JOIN employees e ON e.id=p.employee_id ORDER BY p.id DESC");
          return Db.q(c, "SELECT p.*,e.name,e.emp_code,e.department FROM payroll p JOIN employees e ON e.id=p.employee_id WHERE p.employee_id=? ORDER BY p.id DESC", s.get("id"));
        case "GET /api/payroll/csv": { admin(s);
          StringBuilder sb = new StringBuilder("Employee ID,Name,Month,Gross,Bonus,Loan EMI,PF,Prof Tax,Insurance,Other Deductions,Total Deductions,Net Salary\n");
          for (var r : Db.q(c, "SELECT p.*,e.name,e.emp_code FROM payroll p JOIN employees e ON e.id=p.employee_id WHERE p.month=? ORDER BY e.id", month(param(ex, "month"))))
            sb.append(String.join(",", String.valueOf(r.get("emp_code")), "\"" + r.get("name") + "\"", String.valueOf(r.get("month")), String.valueOf(r.get("gross")), String.valueOf(r.get("bonus")), String.valueOf(r.get("loan_emi")), String.valueOf(r.get("pf")), String.valueOf(r.get("ptax")), String.valueOf(r.get("insurance")), String.valueOf(r.get("other_ded")), String.valueOf(r.get("total_deductions")), String.valueOf(r.get("net")))).append("\n");
          return sb.toString(); }
        case "POST /api/loans/apply": { var b = body(ex);
          if (!"employee".equals(user(s).get("role"))) throw new Fail(403, "Employees only");
          BigDecimal amt = money(b, "amount", true), emi = money(b, "emi", true);
          if (emi.compareTo(amt) > 0) throw new Fail(400, "EMI cannot exceed loan amount");
          Db.x(c, "INSERT INTO loan_applications(employee_id,amount,reason,emi,applied_date,status) VALUES(?,?,?,?,date('now'),'Pending')", s.get("id"), amt, str(b, "reason"), emi);
          return Map.of("ok", true); }
        case "GET /api/loans/applications":
          if ("admin".equals(user(s).get("role"))) return Db.q(c, "SELECT a.*,e.name FROM loan_applications a JOIN employees e ON e.id=a.employee_id ORDER BY a.id DESC");
          return Db.q(c, "SELECT a.*,l.balance,l.status AS loan_status FROM loan_applications a LEFT JOIN loans l ON l.application_id=a.id WHERE a.employee_id=? ORDER BY a.id DESC", s.get("id"));
        case "GET /api/loans/repayments":
          if ("admin".equals(user(s).get("role"))) return Db.q(c, "SELECT r.*,l.employee_id FROM loan_repayments r JOIN loans l ON l.id=r.loan_id ORDER BY r.id DESC");
          return Db.q(c, "SELECT r.* FROM loan_repayments r JOIN loans l ON l.id=r.loan_id WHERE l.employee_id=? ORDER BY r.id DESC", s.get("id"));
        case "POST /api/loans/decide": { admin(s); var b = body(ex); int id = new BigDecimal(str(b, "id")).intValue();
          var a = Db.q(c, "SELECT * FROM loan_applications WHERE id=?", id);
          if (a.isEmpty() || !"Pending".equals(a.get(0).get("status"))) throw new Fail(400, "Application is not pending");
          if (!"true".equalsIgnoreCase(String.valueOf(b.get("approve")))) { Db.x(c, "UPDATE loan_applications SET status='Rejected' WHERE id=?", id); return Map.of("ok", true); }
          BigDecimal amt = money(b, "amount", true), emi = money(b, "emi", true);
          if (emi.compareTo(amt) > 0) throw new Fail(400, "EMI cannot exceed approved amount");
          c.setAutoCommit(false);
          try {
            Db.x(c, "UPDATE loan_applications SET status='Approved' WHERE id=?", id);
            Db.x(c, "INSERT INTO loans(application_id,employee_id,amount,emi,balance,start_month,status) VALUES(?,?,?,?,?,?,'Active')", id, a.get(0).get("employee_id"), amt, emi, amt, month(str(b, "startMonth")));
            c.commit(); return Map.of("ok", true);
          } catch (Exception e) { c.rollback(); throw e; } }
        default: throw new Fail(404, "Not found");
      }
    }
  }

  // Corporate PDF report helpers. Uses PDFBox built-in fonts so the report works offline.
  static final java.awt.Color PDF_NAVY = new java.awt.Color(19, 35, 72);
  static final java.awt.Color PDF_BLUE = new java.awt.Color(48, 91, 166);
  static final java.awt.Color PDF_PALE = new java.awt.Color(239, 244, 252);
  static final java.awt.Color PDF_LINE = new java.awt.Color(218, 226, 238);
  static final java.awt.Color PDF_MUTED = new java.awt.Color(101, 116, 139);
  static final java.awt.Color PDF_GREEN = new java.awt.Color(20, 130, 94);

  static void rect(PDPageContentStream cs,float x,float y,float w,float h,java.awt.Color color,boolean stroke)throws IOException{
    cs.setNonStrokingColor(color); cs.addRect(x,y,w,h);
    if(stroke){cs.setStrokingColor(PDF_LINE);cs.stroke();}else cs.fill();
  }
  static void txt(PDPageContentStream cs,String value,float x,float y,int size,boolean bold,java.awt.Color color)throws IOException{
    String v=String.valueOf(value==null?"":value).replaceAll("[^\\x20-\\x7E]","-");
    if(v.length()>100)v=v.substring(0,97)+"...";
    cs.beginText();cs.setNonStrokingColor(color);cs.setFont(bold?PDType1Font.HELVETICA_BOLD:PDType1Font.HELVETICA,size);cs.newLineAtOffset(x,y);cs.showText(v);cs.endText();
  }
  static void rule(PDPageContentStream cs,float x1,float y,float x2)throws IOException{cs.setStrokingColor(PDF_LINE);cs.setLineWidth(.6f);cs.moveTo(x1,y);cs.lineTo(x2,y);cs.stroke();}
  static BigDecimal num(Object v){try{return v==null?BigDecimal.ZERO:new BigDecimal(v.toString());}catch(Exception e){return BigDecimal.ZERO;}}
  static String moneyPdf(Object v){return String.format(java.util.Locale.US,"INR %,.2f",num(v));}
  static void card(PDPageContentStream cs,float x,float y,float w,String label,String value,java.awt.Color accent)throws IOException{
    rect(cs,x,y,w,58,java.awt.Color.WHITE,true);rect(cs,x,y,4,58,accent,false);
    txt(cs,label.toUpperCase(Locale.ROOT),x+13,y+39,7,false,PDF_MUTED);
    txt(cs,value,x+13,y+17,12,true,PDF_NAVY);
  }
  static Download pdfPayslip(Connection c,int emp,String m) throws Exception {
    var rows=Db.q(c,"SELECT p.*,e.name,e.emp_code,e.department,e.designation,e.email FROM payroll p JOIN employees e ON e.id=p.employee_id WHERE p.employee_id=? AND p.month=?",emp,m);
    if(rows.isEmpty()) throw new Fail(404,"No processed payroll found for this employee and month");
    Map<String,Object> r=rows.get(0); ByteArrayOutputStream out=new ByteArrayOutputStream();
    try(PDDocument doc=new PDDocument()){
      PDPage page=new PDPage(PDRectangle.A4);doc.addPage(page);
      try(PDPageContentStream cs=new PDPageContentStream(doc,page)){
        float w=page.getMediaBox().getWidth();
        rect(cs,0,690,w,152,PDF_NAVY,false);rect(cs,0,686,w,4,PDF_BLUE,false);
        txt(cs,"EMPLOYEE PAYROLL MANAGEMENT",42,795,10,true,new java.awt.Color(190,207,239));
        txt(cs,"EMPLOYEE PAYSLIP",42,756,24,true,java.awt.Color.WHITE);
        txt(cs,"MONTHLY SALARY STATEMENT  |  "+m,42,733,10,false,new java.awt.Color(222,230,244));
        txt(cs,"Generated: "+LocalDate.now(),430,795,8,false,new java.awt.Color(222,230,244));
        txt(cs,"EMPLOYEE INFORMATION",42,658,9,true,PDF_BLUE);
        rect(cs,42,574,511,66,PDF_PALE,false);
        txt(cs,"EMPLOYEE NAME",56,619,7,true,PDF_MUTED);txt(cs,String.valueOf(r.get("name")),56,599,12,true,PDF_NAVY);
        txt(cs,"EMPLOYEE ID",280,619,7,true,PDF_MUTED);txt(cs,String.valueOf(r.get("emp_code")),280,599,10,true,PDF_NAVY);
        txt(cs,"DEPARTMENT / DESIGNATION",410,619,7,true,PDF_MUTED);txt(cs,String.valueOf(r.get("department")),410,601,9,true,PDF_NAVY);
        txt(cs,String.valueOf(r.get("designation")),410,586,8,false,PDF_MUTED);
        txt(cs,"EARNINGS",42,546,10,true,PDF_BLUE);
        float y=522;String[][] earnings={{"Basic salary and allowances","gross"},{"Performance / other bonus","bonus"},{"TOTAL EARNINGS","total_earnings"}};
        for(int i=0;i<earnings.length;i++){boolean total=i==2;if(total)rect(cs,42,y-9,511,27,PDF_PALE,false);txt(cs,earnings[i][0],56,y,9,total,total?PDF_NAVY:PDF_MUTED);txt(cs,moneyPdf(r.get(earnings[i][1])),440,y,9,total,total?PDF_NAVY:PDF_NAVY);rule(cs,42,y-13,553);y-=31;}
        y-=14;txt(cs,"DEDUCTIONS",42,y,10,true,PDF_BLUE);y-=25;
        String[][] ds={{"Optional loan EMI","loan_emi"},{"Provident Fund","pf"},{"Professional Tax","ptax"},{"Insurance","insurance"},{"Other deductions","other_ded"},{"TOTAL DEDUCTIONS","total_deductions"}};
        for(int i=0;i<ds.length;i++){boolean total=i==ds.length-1;if(total)rect(cs,42,y-9,511,27,PDF_PALE,false);txt(cs,ds[i][0],56,y,9,total,total?PDF_NAVY:PDF_MUTED);txt(cs,moneyPdf(r.get(ds[i][1])),440,y,9,total,PDF_NAVY);rule(cs,42,y-13,553);y-=29;}
        rect(cs,42,116,511,58,PDF_NAVY,false);txt(cs,"NET TAKE-HOME PAY",58,151,8,true,new java.awt.Color(205,218,242));txt(cs,moneyPdf(r.get("net")),58,128,20,true,java.awt.Color.WHITE);
        txt(cs,"Payment status: "+r.get("status"),42,91,9,true,PDF_GREEN);
        rule(cs,42,70,250);rule(cs,345,70,553);txt(cs,"Employee acknowledgement",42,55,8,false,PDF_MUTED);txt(cs,"Authorized signatory",345,55,8,false,PDF_MUTED);
        txt(cs,"This is a computer-generated salary statement.",42,29,7,false,PDF_MUTED);
      }
      doc.save(out);
    }
    return new Download(out.toByteArray(),"payslip-"+r.get("emp_code")+"-"+m+".pdf","application/pdf");
  }

  static Download pdfMonthly(Connection c,String m) throws Exception {
    var rows=Db.q(c,"SELECT p.*,e.name,e.emp_code,e.department FROM payroll p JOIN employees e ON e.id=p.employee_id WHERE p.month=? ORDER BY e.department,e.name",m);
    ByteArrayOutputStream out=new ByteArrayOutputStream();
    BigDecimal gross=BigDecimal.ZERO,bonus=BigDecimal.ZERO,ded=BigDecimal.ZERO,net=BigDecimal.ZERO;
    for(var r:rows){gross=gross.add(num(r.get("gross")));bonus=bonus.add(num(r.get("bonus")));ded=ded.add(num(r.get("total_deductions")));net=net.add(num(r.get("net")));}
    final int firstPageRows=11, nextPageRows=17;int pages=Math.max(1, rows.size()<=firstPageRows?1:1+(rows.size()-firstPageRows+nextPageRows-1)/nextPageRows);
    try(PDDocument doc=new PDDocument()){
      for(int pageNo=0;pageNo<pages;pageNo++){
        PDPage page=new PDPage(new PDRectangle(PDRectangle.A4.getHeight(),PDRectangle.A4.getWidth()));doc.addPage(page);
        try(PDPageContentStream cs=new PDPageContentStream(doc,page)){
          float width=page.getMediaBox().getWidth();
          rect(cs,0,0,width,595,java.awt.Color.WHITE,false);
          rect(cs,0,493,width,102,PDF_NAVY,false);rect(cs,0,489,width,4,PDF_BLUE,false);
          txt(cs,"EMPLOYEE PAYROLL MANAGEMENT",30,568,9,true,new java.awt.Color(190,207,239));
          txt(cs,"MONTHLY PAYROLL REPORT",30,536,21,true,java.awt.Color.WHITE);
          txt(cs,"PAY PERIOD  "+m+"     |     GENERATED  "+LocalDate.now(),30,513,8,false,new java.awt.Color(222,230,244));
          txt(cs,"CONFIDENTIAL - INTERNAL HR & FINANCE",width-240,568,7,true,new java.awt.Color(190,207,239));
          if(pageNo==0){
            float gap=10, x=30, cw=(width-60-gap*3)/4;
            card(cs,x,417,cw,"Employees",String.valueOf(rows.size()),PDF_BLUE);x+=cw+gap;
            card(cs,x,417,cw,"Gross payroll",moneyPdf(gross),PDF_BLUE);x+=cw+gap;
            card(cs,x,417,cw,"Total bonuses",moneyPdf(bonus),PDF_GREEN);x+=cw+gap;
            card(cs,x,417,cw,"Total deductions",moneyPdf(ded),new java.awt.Color(198,139,45));
            rect(cs,30,378,width-60,27,PDF_PALE,false);
            txt(cs,"NET PAYROLL",43,388,8,true,PDF_MUTED);txt(cs,moneyPdf(net),width-160,386,12,true,PDF_GREEN);
          }
          float tableTop=pageNo==0?350:445;
          txt(cs,"PAYROLL REGISTER",30,tableTop+17,9,true,PDF_BLUE);
          float[] xs={30,91,230,330,409,475,545,635,812};
          String[] heads={"EMPLOYEE ID","EMPLOYEE","DEPARTMENT","GROSS","BONUS","LOAN EMI","DEDUCTIONS","NET PAY"};
          rect(cs,30,tableTop-20,width-60,22,PDF_NAVY,false);
          for(int j=0;j<heads.length;j++)txt(cs,heads[j],xs[j]+5,tableTop-13, j==0?6:7,true,java.awt.Color.WHITE);
          int from=pageNo==0?0:firstPageRows+(pageNo-1)*nextPageRows;int limit=pageNo==0?firstPageRows:nextPageRows;int to=Math.min(from+limit,rows.size());float y=tableTop-42;
          for(int i=from;i<to;i++){
            var r=rows.get(i);if((i-from)%2==0)rect(cs,30,y-7,width-60,22,new java.awt.Color(247,249,253),false);
            String[] vals={String.valueOf(r.get("emp_code")),String.valueOf(r.get("name")),String.valueOf(r.get("department")),moneyPdf(r.get("gross")),moneyPdf(r.get("bonus")),moneyPdf(r.get("loan_emi")),moneyPdf(r.get("total_deductions")),moneyPdf(r.get("net"))};
            for(int j=0;j<vals.length;j++)txt(cs,vals[j],xs[j]+5,y, j==0?7:7,j==7,j==7?PDF_GREEN:PDF_NAVY);
            rule(cs,30,y-9,width-30);y-=22;
          }
          if(rows.isEmpty()){
            rect(cs,30,tableTop-85,width-60,55,PDF_PALE,false);
            txt(cs,"No processed payroll records were found for "+m+".",48,tableTop-61,10,true,PDF_NAVY);
            txt(cs,"Process payroll for employees before generating this report.",48,tableTop-77,8,false,PDF_MUTED);
          }
          txt(cs,"Records: "+rows.size()+"     |     Page "+(pageNo+1)+" of "+pages,30,24,7,false,PDF_MUTED);
          txt(cs,"ABC Employee Services  |  Confidential payroll information",width-300,24,7,false,PDF_MUTED);
        }
      }
      doc.save(out);
    }
    return new Download(out.toByteArray(),"monthly-payroll-"+m+".pdf","application/pdf");
  }

  static float pdfLine(PDPageContentStream cs,String text,float x,float y,int size,boolean bold)throws IOException{
    txt(cs,text,x,y,size,bold,PDF_NAVY);return y;
  }

  static Object login(HttpExchange ex) throws Exception {
    var b = body(ex); String u = str(b, "username"), pw = str(b, "password"), role = str(b, "role");
    try (Connection c = Db.get()) {
      boolean adm = role.equals("admin");
      var r = Db.q(c, adm ? "SELECT id,username,password_hash FROM admin_users WHERE username=?" : "SELECT id,name AS username,password_hash FROM employees WHERE username=? AND status='Active'", u);
      if (r.isEmpty() || !Db.check(pw, (String) r.get(0).get("password_hash"))) throw new Fail(401, "Invalid credentials");
      String sid = UUID.randomUUID().toString();
      Map<String,Object> m = new HashMap<>(Map.of("role", role, "id", r.get(0).get("id"), "name", r.get(0).get("username")));
      sessions.put(sid, m);
      ex.getResponseHeaders().add("Set-Cookie", "sid=" + sid + "; Path=/; HttpOnly; SameSite=Strict");
      return m;
    }
  }

  static void file(HttpExchange ex, String p) throws IOException {
    try (InputStream in = Main.class.getResourceAsStream("/static" + p)) {
      if (in == null || p.contains("..")) { send(ex, 404, "Not found", "text/plain"); return; }
      send(ex, 200, new String(in.readAllBytes(), StandardCharsets.UTF_8), p.endsWith(".css") ? "text/css" : p.endsWith(".js") ? "text/javascript" : "text/html; charset=utf-8");
    }
  }
  static void send(HttpExchange ex, int code, String body, String type) throws IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", type);
    ex.sendResponseHeaders(code, b.length);
    try (OutputStream o = ex.getResponseBody()) { o.write(b); }
  }
}
