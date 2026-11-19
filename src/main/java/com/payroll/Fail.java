package com.payroll;
public class Fail extends RuntimeException { final int code; Fail(int c,String m){super(m);code=c;} }
