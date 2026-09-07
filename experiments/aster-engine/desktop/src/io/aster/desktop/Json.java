package io.aster.desktop;

import java.util.*;

/** Bounded JSON for the private script-process protocol; no object deserialization. */
final class Json {
    static String quote(String s) {
        StringBuilder b=new StringBuilder("\"");
        for(int i=0;i<s.length();i++) { char c=s.charAt(i); switch(c) {
            case '"': b.append("\\\""); break; case '\\': b.append("\\\\"); break;
            case '\n': b.append("\\n"); break; case '\r': b.append("\\r"); break; case '\t': b.append("\\t"); break;
            default: if(c<32 || Character.isSurrogate(c) || c==0x2028 || c==0x2029) b.append(String.format(Locale.ROOT,"\\u%04x",(int)c)); else b.append(c);
        }} return b.append('"').toString();
    }
    static Object parse(String text) { Parser p=new Parser(text); Object v=p.value(0); p.space(); if(p.i!=text.length()) throw new IllegalArgumentException("Trailing protocol data"); return v; }
    static final class Parser {
        final String s; int i,nodes;
        Parser(String s) { if(s.length()>2*1024*1024) throw new IllegalArgumentException("Protocol size limit"); this.s=s; }
        void space() { while(i<s.length() && " \t\r\n".indexOf(s.charAt(i))>=0) i++; }
        RuntimeException bad() { return new IllegalArgumentException("Invalid script response"); }
        char next() { if(i>=s.length()) throw bad(); return s.charAt(i++); }
        Object value(int depth) {
            if(depth>64 || ++nodes>50000) throw bad(); space(); char c=next();
            if(c=='"') return string();
            if(c=='[') { List<Object> a=new ArrayList<>(); space(); if(i<s.length() && s.charAt(i)==']'){i++;return a;} do { a.add(value(depth+1)); space(); c=next(); if(c==']')return a; if(c!=',')throw bad(); }while(true); }
            if(c=='{') { Map<String,Object> m=new LinkedHashMap<>(); space(); if(i<s.length()&&s.charAt(i)=='}'){i++;return m;} do { space(); if(next()!='"')throw bad(); String k=string(); space(); if(next()!=':')throw bad(); m.put(k,value(depth+1)); space(); c=next(); if(c=='}')return m; if(c!=',')throw bad(); }while(true); }
            i--; int start=i; while(i<s.length() && ",]} \t\r\n".indexOf(s.charAt(i))<0) i++; String word=s.substring(start,i);
            if(word.equals("true"))return Boolean.TRUE; if(word.equals("false"))return Boolean.FALSE; if(word.equals("null"))return null;
            if(!word.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))throw bad();
            double v=Double.parseDouble(word); if(!Double.isFinite(v))throw bad(); return v;
        }
        String string() {
            StringBuilder b=new StringBuilder(); while(true) { char c=next(); if(c=='"')return b.toString(); if(c<32)throw bad(); if(c!='\\'){b.append(c);continue;}
                switch(next()){case '"':b.append('"');break;case '\\':b.append('\\');break;case '/':b.append('/');break;case 'b':b.append('\b');break;case 'f':b.append('\f');break;case 'n':b.append('\n');break;case 'r':b.append('\r');break;case 't':b.append('\t');break;
                    case 'u':int cp=0;for(int j=0;j<4;j++){int d=Character.digit(next(),16);if(d<0)throw bad();cp=(cp<<4)|d;}b.append((char)cp);break;default:throw bad();}
            }
        }
    }
    private Json() {}
}
