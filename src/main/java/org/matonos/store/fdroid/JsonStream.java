package org.matonos.store.fdroid;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reader based JSON parser. The caller can consume package objects one at a time. */
final class JsonStream {
  interface PackageConsumer { void accept(String id, Map<String, Object> value); }
  private final Reader in;
  private int next = -2;
  private JsonStream(Reader in) { this.in = in; }

  static void packages(Reader in, PackageConsumer consumer) throws IOException {
    JsonStream p = new JsonStream(in);
    p.space(); p.expect('{'); p.space();
    if (p.peek() == '}') { p.take(); return; }
    while (true) {
      String key = p.string(); p.space(); p.expect(':'); p.space();
      if ("packages".equals(key)) p.readPackages(consumer); else p.value();
      p.space(); if (p.peek() == '}') { p.take(); break; } p.expect(','); p.space();
    }
    p.space(); if (p.peek() != -1) throw new IllegalArgumentException("Unexpected JSON data");
  }
  private void readPackages(PackageConsumer consumer) throws IOException {
    expect('{'); space(); if (peek() == '}') { take(); return; }
    while (true) {
      String id = string(); space(); expect(':'); space();
      Object value = packageValue();
      if (value instanceof Map) consumer.accept(id, cast(value));
      space(); if (peek() == '}') { take(); return; } expect(','); space();
    }
  }
  private Object packageValue() throws IOException {
    space(); if (peek() != '{') return value();
    take(); Map<String,Object> result=new LinkedHashMap<>(); space(); if(peek()=='}'){take();return result;}
    while(true){String k=string();space();expect(':');space();if("metadata".equals(k)||"versions".equals(k))result.put(k,value());else skip();space();if(peek()=='}'){take();return result;}expect(',');space();}
  }
  private void skip() throws IOException {
    space(); int c=peek();
    if(c=='"'){skipString();return;}
    if(c=='{'){take();space();if(peek()=='}'){take();return;}while(true){skipString();space();expect(':');skip();space();if(peek()=='}'){take();return;}expect(',');space();}}
    if(c=='['){take();space();if(peek()==']'){take();return;}while(true){skip();space();if(peek()==']'){take();return;}expect(',');space();}}
    while((c=peek())!=-1 && ",]} \t\r\n".indexOf(c)<0)take();
  }
  private void skipString() throws IOException { expect('"'); int c; while((c=take())!=-1){if(c=='"')return;if(c=='\\'){c=take();if(c=='u')for(int i=0;i<4;i++)take();}}throw new IllegalArgumentException("Unterminated JSON string"); }
  private Object value() throws IOException {
    space(); int c = peek();
    if (c == '{') { take(); Map<String,Object> m=new LinkedHashMap<>(); space(); if(peek()=='}'){take();return m;} while(true){String k=string();space();expect(':');m.put(k,value());space();if(peek()=='}'){take();return m;}expect(',');space();} }
    if (c == '[') { take(); List<Object> a=new ArrayList<>();space();if(peek()==']'){take();return a;}while(true){a.add(value());space();if(peek()==']'){take();return a;}expect(',');} }
    if (c == '"') return string();
    StringBuilder b=new StringBuilder(); while((c=peek())!=-1 && ",]} \t\r\n".indexOf(c)<0) b.append((char)take());
    String s=b.toString(); if("true".equals(s))return Boolean.TRUE;if("false".equals(s))return Boolean.FALSE;if("null".equals(s))return null;
    try{return s.indexOf('.')>=0||s.indexOf('e')>=0||s.indexOf('E')>=0?Double.valueOf(s):Long.valueOf(s);}catch(NumberFormatException e){throw new IllegalArgumentException("Bad JSON value",e);}
  }
  private String string() throws IOException {
    expect('"'); StringBuilder b=new StringBuilder(); int c;
    while((c=take())!=-1){if(c=='"')return b.toString();if(c!='\\'){b.append((char)c);continue;}c=take();switch(c){case '"':case '\\':case '/':b.append((char)c);break;case 'b':b.append('\b');break;case 'f':b.append('\f');break;case 'n':b.append('\n');break;case 'r':b.append('\r');break;case 't':b.append('\t');break;case 'u':int n=0;for(int i=0;i<4;i++){int d=Character.digit(take(),16);if(d<0)throw new IllegalArgumentException("Bad unicode escape");n=(n<<4)|d;}b.append((char)n);break;default:throw new IllegalArgumentException("Bad JSON escape");}}
    throw new IllegalArgumentException("Unterminated JSON string");
  }
  private void space() throws IOException { while(" \t\r\n".indexOf(peek())>=0)take(); }
  private int peek() throws IOException { if(next==-2)next=in.read();return next; }
  private int take() throws IOException { int c=peek();next=-2;return c; }
  private void expect(char c) throws IOException { if(take()!=c)throw new IllegalArgumentException("Expected '"+c+"'"); }
  @SuppressWarnings("unchecked") private static Map<String,Object> cast(Object o){return (Map<String,Object>)o;}
}
